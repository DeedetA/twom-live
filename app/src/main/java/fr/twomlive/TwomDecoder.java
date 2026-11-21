package fr.twomlive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dechiffrement et lecture des messages envoyes par le serveur de TWOM.
 *
 * Trame TCP = [longueur u16 BE, en-tete inclus][charge chiffree].
 * Chiffrement par trame : cle k (1 octet) ; clair = rotr(c ^ k, (k & 7) ou 1).
 * Le dernier octet de la charge est un octet de controle.
 * 0x23 = lot : [0x23][n] puis n x [longueur u16 BE incluse][message].
 */
public final class TwomDecoder {
    private TwomDecoder() {}

    static final boolean[] KNOWN_OPS = new boolean[256];
    static {
        for (int op : new int[]{0x23, 0x43, 0x46, 0x47, 0x49, 0x4d, 0x5b, 0x6c, 0xa0, 0xaa, 0xbc, 0xd6}) KNOWN_OPS[op] = true;
    }

    static int rotr(int b, int r) {
        r &= 7;
        return ((b >>> r) | (b << (8 - r))) & 0xff;
    }

    static int rotFor(int k) {
        int r = k & 7;
        return r == 0 ? 1 : r;
    }

    static byte[] dec(byte[] p, int k) {
        int r = rotFor(k);
        byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) rotr((p[i] & 0xff) ^ k, r);
        return o;
    }

    static int guessKey(byte[] p) {
        int[] c = new int[256];
        int best = 0;
        for (byte b : p) {
            int v = b & 0xff;
            c[v]++;
        }
        for (int v = 0; v < 256; v++) if (c[v] > c[best]) best = v;
        // en cas d'egalite, Python garde le premier rencontre : on fait pareil
        int max = c[best];
        for (byte b : p) if (c[b & 0xff] == max) return b & 0xff;
        return best;
    }

    static byte[] sub(byte[] a, int from, int to) {
        byte[] o = new byte[Math.max(0, to - from)];
        System.arraycopy(a, from, o, 0, o.length);
        return o;
    }

    static List<byte[]> splitBatch(byte[] x) {
        if (x.length < 2) return null;
        int n = x[1] & 0xff, i = 2;
        List<byte[]> out = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            if (i + 2 > x.length) return null;
            int L = ((x[i] & 0xff) << 8) | (x[i + 1] & 0xff);
            if (L < 3 || i + L > x.length) return null;
            out.add(sub(x, i + 2, i + L));
            i += L;
        }
        return i == x.length ? out : null;
    }

    public static String hex(byte[] a, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < a.length; i++) sb.append(String.format("%02x", a[i] & 0xff));
        return sb.toString();
    }

    /** messages clairs d'une trame (payload sans l'en-tete de longueur). */
    public static List<byte[]> decodeFrame(byte[] p, Map<String, ?> knownIds) {
        List<byte[]> none = new ArrayList<>();
        if (p.length <= 2) return none;
        int k0 = guessKey(p);
        byte[] x = dec(p, k0);
        byte[] body = sub(x, 0, x.length - 1);
        if (body.length > 0 && (body[0] & 0xff) == 0x23) {
            List<byte[]> m = splitBatch(body);
            if (m != null) return m;
        } else if (body.length > 0 && KNOWN_OPS[body[0] & 0xff]) {
            List<byte[]> l = new ArrayList<>();
            l.add(body);
            return l;
        }
        for (int k = 0; k < 256; k++) {
            if (k == k0) continue;
            byte[] y = dec(p, k);
            byte[] b = sub(y, 0, y.length - 1);
            if (b.length == 0) continue;
            int op = b[0] & 0xff;
            if (op == 0x23) {
                List<byte[]> m = splitBatch(b);
                if (m != null) return m;
            } else if (op == 0x4c && b.length == 14 && b[1] == 0 && b[6] == 0 && isIdAt(b, 2) && isIdAt(b, 7)
                    && b[11] == 1 && b[12] == 1) {
                // [4c][00][ID][00][ID][01 01][n] : trop court pour que la cle devinee soit fiable
                List<byte[]> l = new ArrayList<>();
                l.add(b);
                return l;
            } else if ((op == 0x47 || op == 0x6c) && b.length >= 5 && knownIds.containsKey(hex(b, 1, 4))) {
                List<byte[]> l = new ArrayList<>();
                l.add(b);
                return l;
            }
        }
        List<byte[]> l = new ArrayList<>();
        l.add(body);
        return l;
    }

    // ------------------------------------------------------------ sens telephone -> serveur
    static final boolean[] C2S_OPS = new boolean[256];
    static {
        for (int op : new int[]{0x0f, 0x27, 0x43, 0x4f, 0x67, 0x68, 0x69, 0x7e, 0x91, 0x9e, 0xa4, 0xa8, 0xca}) C2S_OPS[op] = true;
    }
    static byte[] decR(byte[] p, int k, int r) {
        byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) rotr((p[i] & 0xff) ^ k, r);
        return o;
    }

    /** le message d'attaque (0x4f) se termine par un long remplissage : un motif de 4 octets repete */
    static boolean periodic4(byte[] p) {
        if (p.length < 200) return false;
        int same = 0, n = 0;
        for (int i = p.length / 2; i < p.length - 1; i++) {
            n++;
            if (p[i] == p[i - 4]) same++;
        }
        return same > n * 8 / 10;
    }

    /** cle d'une trame d'attaque : donne 0x4f et des zeros dans l'en-tete (octets bruts = cle) */
    static int attackKey(byte[] p) {
        int best = -1, bestScore = 0;
        for (int k = 0; k < 256; k++) {
            if (rotr((p[0] & 0xff) ^ k, rotFor(k)) != 0x4f) continue;
            int score = 0;
            for (int i = 1; i < 13 && i < p.length; i++) if ((p[i] & 0xff) == k) score++;
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    static boolean isIdAt(byte[] m, int i) {
        if (i + 4 > m.length) return false;
        int b0 = m[i] & 0xff;
        return b0 >= ID_MIN && b0 <= ID_MAX && (m[i + 1] & 0x80) != 0 && (m[i + 2] & 0x80) != 0 && (m[i + 3] & 0x80) == 0;
    }

    /**
     * Forme attendue de chaque type envoye par le telephone. Une mauvaise cle peut donner par hasard
     * un type connu (une utilisation d'objet lue comme 0x69, par ex.) : on verifie donc la forme.
     */
    static boolean validClient(byte[] x) {
        if (x.length == 0 || !C2S_OPS[x[0] & 0xff]) return false;
        switch (x[0] & 0xff) {
            case 0x69: return x.length == 10 && x[1] == 1 && x[2] == 0 && x[3] == 0;
            case 0x68: return x.length == 10 && x[1] == 0 && x[2] == 1;
            case 0x7e: return x.length == 7;
            // 0d : utiliser un objet ; 01 / 03 : equiper / retirer
            case 0xa4: return x.length >= 12 && (x[1] == 0x0d || x[1] == 0x01 || x[1] == 0x03) && isIdAt(x, 2) && x[6] == 0;
            case 0x91: return x.length >= 8 && isIdAt(x, 2);
            // [9e][canal][longueur en octets][texte UTF-16] : ton message de chat
            case 0x9e: return x.length >= 5 && (x[1] & 0xff) < 0x20 && (x[2] & 0xff) == x.length - 3 && (x[2] & 1) == 0;
            case 0x67: return x.length == 5 && isIdAt(x, 1);
            case 0xca: return x.length == 6 && x[1] == 7 && isIdAt(x, 2);
            case 0x0f: return x.length == 6 && x[1] == 1 && isIdAt(x, 2);
            case 0xa8: return x.length >= 7 && x[1] == 0;
            case 0x43: return x.length >= 18 && x.length <= 28;
            case 0x4f: return x.length > 1000;
            default: return true;
        }
    }

    /**
     * Message clair d'une trame envoyee par le telephone (sans l'octet de controle), ou null.
     * Meme chiffrement que dans l'autre sens, mais les petites trames trompent souvent la
     * devinette de cle : on choisit alors la cle et le decalage qui donnent un type connu.
     */
    public static byte[] decodeClientFrame(byte[] p) { return decodeClientFrame(p, null); }

    /** position de ton ID dans les types qui le contiennent, ou -1 */
    static int selfPos(int op) {
        switch (op) {
            case 0x91: case 0xa4: case 0xca: case 0x0f: return 2;
            case 0x67: return 1;
            default: return -1;
        }
    }

    /**
     * selfId (hex, ex. "9698e56c") : ton identifiant actuel, s'il est connu. Il sert de dernier recours
     * pour les tres petits messages : on n'accepte alors que la cle qui fait apparaitre ton ID a sa place.
     */
    public static byte[] decodeClientFrame(byte[] p, String selfId) {
        if (p.length <= 2) return null;
        if (periodic4(p)) {                          // attaque : en-tete + ~2 Ko de remplissage
            int k = attackKey(p);
            if (k >= 0) {
                byte[] x = dec(p, k);
                return sub(x, 0, x.length - 1);
            }
        }
        int k0 = guessKey(p);
        byte[] x = sub(dec(p, k0), 0, p.length - 1);
        if (validClient(x)) return x;
        int[] cnt = new int[256];
        for (byte b : p) cnt[b & 0xff]++;
        Integer[] keys = new Integer[256];
        for (int i = 0; i < 256; i++) keys[i] = i;
        java.util.Arrays.sort(keys, (a, b) -> cnt[b] - cnt[a]);
        for (int k : keys) {
            if (cnt[k] < 2) break;
            int base = (p[0] & 0xff) ^ k;
            int r0 = rotFor(k);
            for (int t = -1; t < 8; t++) {
                int r = t < 0 ? r0 : t;                  // d'abord le decalage habituel, puis les autres
                if (!C2S_OPS[rotr(base, r)]) continue;
                byte[] y = sub(decR(p, k, r), 0, p.length - 1);
                if (validClient(y)) return y;
            }
        }
        if (selfId != null) {
            for (int k = 0; k < 256; k++) {
                for (int t = -1; t < 8; t++) {
                    int r = t < 0 ? rotFor(k) : t;
                    int op = rotr((p[0] & 0xff) ^ k, r);
                    int pos = selfPos(op);
                    if (pos < 0 || !C2S_OPS[op]) continue;
                    byte[] y = sub(decR(p, k, r), 0, p.length - 1);
                    if (validClient(y) && hex(y, pos, 4).equals(selfId)) return y;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ champs
    /** varint MSB-first ; bit7 = suite ; sur le 1er octet bit6 = signe. res[0]=valeur res[1]=index suivant */
    public static long[] varint(byte[] m, int i, boolean signed) {
        int b = m[i++] & 0xff;
        boolean neg = signed && (b & 0x40) != 0;
        long v = b & (signed ? 0x3f : 0x7f);
        while ((b & 0x80) != 0) {
            b = m[i++] & 0xff;
            v = (v << 7) | (b & 0x7f);
        }
        return new long[]{neg ? -v : v, i};
    }

    /**
     * Identifiant d'entite = entier sur 4 octets (varint). Le serveur les numerote avec un compteur
     * qui ne fait que monter : le premier octet est passe de 0x8b (novembre) a 0x91. On accepte
     * donc une large plage ; 0x84 et moins restent exclus (autres champs de 4 octets).
     */
    public static final int ID_MIN = 0x88, ID_MAX = 0xbf;

    static final Pattern REC = Pattern.compile(
            "(?=([\\x88-\\xbf][\\x80-\\xff][\\x80-\\xff][\\x00-\\x7f])([\\x80-\\xbf][\\x00-\\x7f])([\\x80-\\xbf][\\x00-\\x7f]))",
            Pattern.DOTALL);
    static final Pattern NAME = Pattern.compile(
            "([\\x80-\\xff]*[\\x00-\\x7f])([\\x02-\\x40])((?:[\\x20-\\x7e]\\x00)+)", Pattern.DOTALL);
    static final Pattern U16 = Pattern.compile(
            "([\\x01-\\xfe])((?:[\\x20-\\x7e\\xa0-\\xff]\\x00)+)", Pattern.DOTALL);

    static String latin(byte[] m) {
        return new String(m, StandardCharsets.ISO_8859_1);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static final class Entity {
        public String id, name;
        public int x, y;
        public Integer lvl;
        /** HP actuels et maximum (-1 si inconnus) : [HP][nom]... 07 01 1f 12 [niveau] 00 00 00 [HP max] */
        public int hp = -1, maxHp = -1;
    }

    public static List<Entity> parseEntities(byte[] m, int start) {
        String s = latin(m);
        List<int[]> starts = new ArrayList<>();       // off, x, y
        List<String> ids = new ArrayList<>();
        Matcher r = REC.matcher(s);
        int from = start;
        while (from <= s.length() && r.find(from)) {
            int off = r.start();
            byte[] xb = bytes(r.group(2)), yb = bytes(r.group(3));
            starts.add(new int[]{off, (int) varint(xb, 0, true)[0], (int) varint(yb, 0, true)[0]});
            ids.add(hex(bytes(r.group(1)), 0, 4));
            from = off + 1;
        }
        List<Entity> out = new ArrayList<>();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        Matcher n = NAME.matcher(s);
        n.region(start, s.length());
        while (n.find()) {
            int L = n.group(2).charAt(0);
            String u = n.group(3);
            if (L != u.length() || L < 4) continue;
            int pos = n.start(2);
            int idx = -1;
            for (int j = 0; j < starts.size(); j++) if (starts.get(j)[0] < pos) idx = j;
            if (idx < 0 || used.contains(starts.get(idx)[0])) continue;
            used.add(starts.get(idx)[0]);
            Entity e = new Entity();
            e.id = ids.get(idx);
            e.x = starts.get(idx)[1];
            e.y = starts.get(idx)[2];
            e.name = new String(bytes(u), StandardCharsets.UTF_16LE);
            int a = pos + 1 + L;
            if (a + 5 <= m.length && (m[a] & 0xff) == 0x07 && (m[a + 1] & 0xff) == 0x01
                    && (m[a + 2] & 0xff) == 0x1f && (m[a + 3] & 0xff) == 0x12) {
                e.lvl = m[a + 4] & 0xff;
                if (a + 9 <= m.length && m[a + 5] == 0 && m[a + 6] == 0 && m[a + 7] == 0) {
                    long max = varint(m, a + 8, false)[0];
                    // les HP actuels finissent juste avant la longueur du nom
                    int k = pos - 1;
                    if (k > 0 && (m[k] & 0x80) == 0) {
                        int b = k;
                        while (b - 1 > 0 && (m[b - 1] & 0x80) != 0 && k - b < 2) b--;
                        long hp = varint(m, b, false)[0];
                        if (max > 0 && max < 10_000_000 && hp >= 0 && hp <= max) {
                            e.maxHp = (int) max;
                            e.hp = (int) hp;
                        }
                    }
                }
            }
            out.add(e);
        }
        return out;
    }

    /** [auteur, texte] ; auteur vide pour un message systeme ; null si rien. */
    public static String[] parseChat(byte[] m) {
        String s = latin(m);
        Matcher u = U16.matcher(s);
        u.region(1, s.length());
        List<String> parts = new ArrayList<>();
        while (u.find()) {
            String g = u.group(2);
            if (u.group(1).charAt(0) == g.length()) parts.add(new String(bytes(g), StandardCharsets.UTF_16LE));
        }
        if (parts.isEmpty()) return null;
        if (parts.size() >= 2) return new String[]{parts.get(0), parts.get(1)};
        return new String[]{"", parts.get(0)};
    }

    /** decoupe le flux serveur -> client en trames. */
    public static final class FrameReader {
        private byte[] buf = new byte[0];

        public List<byte[]> feed(byte[] data, int off, int len) {
            byte[] nb = new byte[buf.length + len];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(data, off, nb, buf.length, len);
            buf = nb;
            List<byte[]> out = new ArrayList<>();
            int i = 0;
            while (buf.length - i >= 2) {
                int L = ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
                if (L < 2) { buf = new byte[0]; return out; }   // flux incoherent : on abandonne
                if (buf.length - i < L) break;
                out.add(sub(buf, i + 2, i + L));
                i += L;
            }
            buf = sub(buf, i, buf.length);
            return out;
        }
    }
}
