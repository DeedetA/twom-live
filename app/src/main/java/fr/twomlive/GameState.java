package fr.twomlive;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Etat du monde vu par le client : entites, mobs en vue, evenements. Aucune dependance Android. */
public final class GameState {

    public interface Listener {
        void onEvent(String line, int kind);
        void onMobsChanged();
    }

    public static final GameState INSTANCE = new GameState();

    public volatile boolean showChat = true;
    public volatile boolean verbose = false;
    public volatile boolean showSent = true;

    // ------------------------------------------------------------ mode "Raw" : tous les messages
    /** recoit chaque ligne brute (fichier d'enregistrement cote Android) */
    public interface RawSink { void write(String line); }

    public volatile boolean raw = false;
    public volatile RawSink rawSink = null;
    private final SimpleDateFormat rawFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final int SCREEN_HEX = 32;           // octets montres a l'ecran (le fichier a tout)

    static final Map<Integer, String> SERVER_OPS = new HashMap<>();
    static final Map<Integer, String> CLIENT_OPS = new HashMap<>();
    static {
        // "?" = deduit par recoupement, pas encore certain
        Object[][] s = {{0x0a, "character list"}, {0x2b, "handshake"}, {0x43, "move"}, {0x45, "server time"},
                {0x46, "spawn"}, {0x47, "remove"}, {0x49, "death"}, {0x4d, "attack"}, {0x57, "buff/cooldown timer"},
                {0x5a, "ack"}, {0x5b, "effect"}, {0x5c, "stat update?"}, {0x5e, "map info"},
                {0x62, "character info"}, {0x64, "enter map"}, {0x66, "ack"}, {0x6c, "update"},
                {0x7a, "server url"}, {0x86, "item obtained"}, {0x87, "inventory slot"}, {0x8c, "gold change"}, {0x91, "NPC dialog"},
                {0x95, "pet info"}, {0x9a, "map data"}, {0xa0, "chat"}, {0xa4, "status"}, {0xa6, "combat state?"},
                {0xa9, "player info"}, {0xaa, "status"}, {0xac, "guild/friends list"}, {0xb3, "pet update"},
                {0xbc, "system message"}, {0xca, "character state"}, {0xd6, "entities in view"},
                {0xd7, "nearby list?"}};
        for (Object[] o : s) SERVER_OPS.put((Integer) o[0], (String) o[1]);
        Object[][] c = {{0x27, "login"}, {0x43, "move"}, {0x4f, "attack/skill"}, {0x68, "ping"},
                {0x69, "request"}, {0x7e, "client version"}, {0xa4, "use item"}, {0xa8, "UI action"},
                {0x91, "NPC dialog"}, {0x9e, "chat"}, {0x67, "enter map"}, {0xca, "enter map"}, {0x0f, "enter map"}};
        for (Object[] o : c) CLIENT_OPS.put((Integer) o[0], (String) o[1]);
    }

    static String spacedHex(byte[] m, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < m.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02x", m[i] & 0xff));
        }
        return sb.toString();
    }

    private void rawLine(boolean fromServer, String head, byte[] m, int shown, String tail) {
        if (!raw) return;
        String dir = fromServer ? "<-" : "->";
        int n = m == null ? 0 : Math.min(shown, m.length);
        String full = m == null ? "" : spacedHex(m, 0, n) + (tail != null ? " " + tail : "");
        RawSink sink = rawSink;
        if (sink != null) {
            String t;
            synchronized (rawFmt) { t = rawFmt.format(new Date()); }
            try { sink.write(t + " " + dir + " " + head + "  " + full); } catch (RuntimeException ignored) { }
        }
        String screen = m == null ? "" : spacedHex(m, 0, Math.min(n, SCREEN_HEX)) + (n > SCREEN_HEX ? " ..." : (tail != null ? " " + tail : ""));
        log(fromServer ? RECU : ENVOYE, dir + " " + head + "  " + screen, null);
    }

    /** message recu du serveur, tel quel */
    public void rawServer(byte[] m) {
        if (!raw || m == null || m.length == 0) return;
        int op = m[0] & 0xff;
        String name = SERVER_OPS.get(op);
        rawLine(true, String.format(Locale.US, "%02x %s (%d B)", op, name != null ? name : "?", m.length), m, m.length, null);
    }

    /** message envoye par le telephone ; frame = trame chiffree si le dechiffrement a echoue */
    public void rawClient(byte[] m, byte[] frame) {
        if (!raw) return;
        if (m == null) {
            String what = isMapLoading(frame) ? "map loading" : "undecoded";
            rawLine(false, String.format(Locale.US, "?? %s (%d B, encrypted)", what, frame.length), frame, frame.length, null);
            return;
        }
        if (m.length == 0) return;
        int op = m[0] & 0xff;
        String name = CLIENT_OPS.get(op);
        String head = String.format(Locale.US, "%02x %s (%d B)", op, name != null ? name : "?", m.length);
        if (op == 0x27) {
            // peut contenir le jeton de session : jamais affiche ni enregistre
            rawLine(false, head, new byte[]{m[0]}, 1, "[content hidden: may contain your session token]");
        } else if (op == 0x4f && m.length > 200) {
            rawLine(false, head, m, 20, "... [+" + (m.length - 20) + " B anti-cheat block omitted]");
        } else {
            rawLine(false, head, m, m.length, null);
        }
    }
    public int attacksSent = 0;

    private final Map<String, TwomDecoder.Entity> ents = new HashMap<>();
    private final Map<String, TwomDecoder.Entity> mobs = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> hits = new HashMap<>();
    /** categories de lignes du journal (pour les couleurs) */
    public static final int RECU = 0, ENVOYE = 1, DROP = 2, SYSTEME = 3, ALERTE = 4;

    /** prevenu quand une entite dont le nom est surveille apparait ou entre dans le champ de vision */
    public interface AlertListener {
        void onMatch(TwomDecoder.Entity e, boolean spawned);
    }

    private final List<AlertListener> alertListeners = new CopyOnWriteArrayList<>();
    private volatile List<String> watch = new ArrayList<>();
    private final java.util.Set<String> alerted = new java.util.HashSet<>();
    private final java.util.Set<String> inView = new java.util.HashSet<>();
    private boolean hasSelf = false;
    private int selfX, selfY;

    /** position de ton personnage, ou null si encore inconnue */
    public synchronized int[] self() { return hasSelf ? new int[]{selfX, selfY} : null; }

    synchronized void setSelf(long x, long y) { hasSelf = true; selfX = (int) x; selfY = (int) y; }

    static final String[] DIRS = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};

    /**
     * Ou se trouve (x,y) par rapport a toi, ex. "300 north-east" ; null si ta position est inconnue.
     * Le nord est en haut de l'ecran (y plus petit).
     */
    public synchronized String relative(int x, int y) {
        if (!hasSelf) return null;
        double dx = x - selfX, dy = selfY - y;          // dy > 0 = vers le haut = nord
        double d = Math.hypot(dx, dy);
        if (d < 30) return "right next to you";
        int sector = (int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
        sector = ((sector % 8) + 8) % 8;
        long dist = Math.round(d / 10.0) * 10;
        return dist + " " + DIRS[sector];
    }

    /** connexion au jeu fermee : plus rien n'est en vue */
    public synchronized void clearView() {
        inView.clear(); mobs.clear(); hits.clear(); alerted.clear(); hasSelf = false;
        for (Listener l : listeners) l.onMobsChanged();
    }

    public void addAlertListener(AlertListener l) { if (!alertListeners.contains(l)) alertListeners.add(l); }
    public void removeAlertListener(AlertListener l) { alertListeners.remove(l); }

    /** noms surveilles, separes par des virgules ; la casse est ignoree, un morceau du nom suffit */
    public void setWatch(String raw) {
        List<String> w = new ArrayList<>();
        if (raw != null) for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) w.add(t);
        }
        watch = w;
        synchronized (this) { alerted.clear(); }
    }

    public boolean matches(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String t : watch) if (n.contains(t)) return true;
        return false;
    }

    private void checkWatch(TwomDecoder.Entity e, boolean spawned) {
        if (watch.isEmpty() || e.name.endsWith("Gold") || !matches(e.name)) return;
        synchronized (this) {
            if (!alerted.add(e.id)) return;          // une seule alerte tant qu'il reste en vue
        }
        String lvl = e.lvl != null ? " (lvl " + e.lvl + ")" : "";
        String rel = relative(e.x, e.y);
        String where = rel != null ? " - " + rel : "";
        log(ALERTE, "ALERT: " + e.name + lvl + (spawned ? " spawned" : " in view") + " at (" + e.x + "," + e.y + ")" + where + " - ID " + e.id,
                new Object[]{"alert", e.id, e.name, e.lvl, e.x, e.y, (spawned ? "spawned" : "in view") + (rel != null ? ", " + rel : "")});
        for (AlertListener l : alertListeners) {
            try { l.onMatch(e, spawned); } catch (RuntimeException ignored) { }
        }
    }

    public static final class Line {
        public final String text;
        public final int kind;
        public Line(String text, int kind) { this.text = text; this.kind = kind; }
    }

    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("time;event;id;name;level;x;y;detail\n");
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    public int deaths = 0;

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized List<String> lines() {
        List<String> o = new ArrayList<>();
        for (Line l : lines) o.add(l.text);
        return o;
    }

    public synchronized List<Line> entries() { return new ArrayList<>(lines); }

    public synchronized String csv() { return csv.toString(); }
    public synchronized Map<String, TwomDecoder.Entity> knownIds() { return new HashMap<>(ents); }

    public synchronized void reset() {
        ents.clear(); mobs.clear(); hits.clear(); lines.clear(); alerted.clear(); inView.clear(); hasSelf = false;
        recentDeaths.clear(); deaths = 0; attacksSent = 0; selfId = null;
        loading = false; loadingUntil = 0;
        csv.setLength(0);
        csv.append("time;event;id;name;level;x;y;detail\n");
        for (Listener l : listeners) l.onMobsChanged();
    }

    public void info(String text) { log(SYSTEME, text, null); }

    /** messages de diagnostic, affiches seulement avec l'option "Details" */
    public void debug(String text) { if (verbose) log(SYSTEME, text, null); }

    private String selfId = null;

    public synchronized String selfIdHex() { return selfId; }

    private String nom(String id) {
        if (id.equals(selfId)) return "you";
        TwomDecoder.Entity e = ents.get(id);
        return e != null ? e.name : "?" + id;
    }

    private void log(int kind, String text, Object[] ev) {
        String h = fmt.format(new Date());
        String line = "[" + h + "] " + text;
        synchronized (this) {
            lines.add(new Line(line, kind));
            if (lines.size() > 600) lines.remove(0);
            if (ev != null) {
                csv.append(h);
                for (Object o : ev) csv.append(';').append(o == null ? "" : String.valueOf(o).replace(';', ','));
                csv.append('\n');
            }
        }
        for (Listener l : listeners) l.onEvent(line, kind);
    }

    private void mobsChanged() { for (Listener l : listeners) l.onMobsChanged(); }

    /** resume des mobs en vue, pour l'ecran principal */
    public synchronized String mobsSummary() {
        if (mobs.isEmpty()) return "No mobs in view.";
        Map<String, Integer> c = new TreeMap<>();
        for (TwomDecoder.Entity m : mobs.values()) c.merge(m.name + " (lvl " + m.lvl + ")", 1, Integer::sum);
        StringBuilder sb = new StringBuilder(mobs.size() + " mob(s) in view:");
        for (Map.Entry<String, Integer> e : c.entrySet()) sb.append("\n  ").append(e.getKey()).append(" x").append(e.getValue());
        sb.append("\n");
        List<Map.Entry<String, TwomDecoder.Entity>> l = new ArrayList<>(mobs.entrySet());
        l.sort((a, b) -> a.getValue().name.compareTo(b.getValue().name));
        for (Map.Entry<String, TwomDecoder.Entity> e : l) {
            TwomDecoder.Entity m = e.getValue();
            sb.append("\n- ").append(m.name).append(" (").append(m.x).append(',').append(m.y).append(')');
            Map<String, Integer> att = hits.get(e.getKey());
            if (att != null && !att.isEmpty()) sb.append(" - fighting ").append(String.join(", ", att.keySet()));
        }
        return sb.toString();
    }

    public void connection(boolean open, String dest) {
        log(SYSTEME, open ? "Game server detected (" + dest + ")" : "Game server connection closed (" + dest + ")", null);
    }

    static boolean isId(byte[] m, int i) {
        int b0 = i < m.length ? m[i] & 0xff : 0;
        return i + 4 <= m.length && b0 >= TwomDecoder.ID_MIN && b0 <= TwomDecoder.ID_MAX && (m[i + 1] & 0x80) != 0
                && (m[i + 2] & 0x80) != 0 && (m[i + 3] & 0x80) == 0;
    }

    // ------------------------------------------------------------ chargement de map
    // A chaque connexion ou changement de map, le telephone envoie une petite serie de messages
    // chiffres trop courts pour etre dechiffres (11, 6, 3 et 5 octets). On les reconnait au contexte.
    private long loadingUntil = 0;
    private boolean loading = false;

    long now() { return System.currentTimeMillis(); }

    private synchronized void startLoading() {
        loadingUntil = now() + 8000;
        if (!loading) { loading = true; log(SYSTEME, "Map loading...", null); }
    }

    /** vrai si cette trame illisible fait partie d'un chargement de map */
    public synchronized boolean isMapLoading(byte[] frame) {
        if (frame.length == 11) return true;
        return frame.length <= 11 && now() <= loadingUntil;
    }

    /** une trame du telephone qu'on n'a pas pu dechiffrer */
    public void clientUndecoded(byte[] frame) {
        if (isMapLoading(frame)) {
            if (frame.length == 11) startLoading();
        } else if (showSent && verbose) {
            log(ENVOYE, "Undecoded message (" + frame.length + " B)", null);
        }
    }

    /** un message clair envoye par le telephone au serveur */
    public void clientMessage(byte[] m) {
        if (m == null) {
            if (showSent && verbose) log(ENVOYE, "Undecoded message", null);
            return;
        }
        if (m.length == 0) return;
        int op = m[0] & 0xff;
        try {
            switch (op) {
                case 0x4f: {
                    // [4f][?][?][y][?][?][x][compteur][competence] puis la cible si attaque de base
                    int i = 1;
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    long[] yv = TwomDecoder.varint(m, i, true); i = (int) yv[1];
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    long[] xv = TwomDecoder.varint(m, i, true); i = (int) xv[1];
                    int skill = m[i + 1] & 0xff;
                    i += 2;
                    // une cible peut suivre (attaque de base ou competence ciblee) ; le remplissage, lui,
                    // repete le meme motif de 4 octets : on ne le confond pas avec un identifiant
                    boolean filler = i + 8 <= m.length && m[i] == m[i + 4] && m[i + 1] == m[i + 5]
                            && m[i + 2] == m[i + 6] && m[i + 3] == m[i + 7];
                    String tgt = (isId(m, i) && !filler) ? TwomDecoder.hex(m, i, 4) : null;
                    String tname;
                    setSelf(xv[0], yv[0]);
                    synchronized (this) {
                        attacksSent++;
                        tname = tgt == null ? null : nom(tgt);
                    }
                    if (!showSent) break;
                    if (skill == 0) {
                        log(ENVOYE, "You attack " + (tname != null ? tname : "your target") + " (basic attack)",
                                new Object[]{"attack_sent", tgt, tname, "", xv[0], yv[0], "basic attack"});
                    } else {
                        String on = tname != null ? " on " + tname : "";
                        log(ENVOYE, "You cast skill #" + skill + on + " from (" + xv[0] + "," + yv[0] + ")",
                                new Object[]{"skill_sent", tgt, tname, "", xv[0], yv[0], "skill " + skill});
                    }
                    break;
                }
                case 0x43: {
                    int i = 1;
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    long[] xv = TwomDecoder.varint(m, i, true); i = (int) xv[1];
                    i = (int) TwomDecoder.varint(m, i, true)[1];
                    i += 4;
                    long y = TwomDecoder.varint(m, i, true)[0];
                    setSelf(xv[0], y);
                    if (showSent && verbose) log(ENVOYE, "You move to (" + xv[0] + "," + y + ")", null);
                    break;
                }
                case 0x27:
                    if (showSent) log(ENVOYE, "Login info sent to the server (device, game version)", null);
                    break;
                case 0x91: {
                    // [91][action][ton ID][PNJ]... : tu parles a un PNJ (achat, vente, teleportation...)
                    if (!showSent || m.length < 8) break;
                    int sub = m[1] & 0xff;
                    long[] npc = TwomDecoder.varint(m, 6, false);
                    int i = (int) npc[1];
                    String who = "NPC #" + npc[0];
                    switch (sub) {
                        case 0x00: log(ENVOYE, "You talk to " + who, null); break;
                        case 0x03: log(ENVOYE, "You close the dialog with " + who, null); break;
                        case 0x02:
                            if (i < m.length) log(ENVOYE, "You choose option " + TwomDecoder.varint(m, i, false)[0] + " (" + who + ")", null);
                            break;
                        case 0x0c: case 0x0e: {
                            // [case globale = sac x 12 + case][objet]...[quantite][00]
                            if (i + 2 >= m.length) break;
                            int slot = m[i] & 0xff;
                            long item = TwomDecoder.varint(m, i + 1, false)[0];
                            int n = m.length;
                            String qty = (m[n - 1] == 0 && (m[n - 2] & 0x80) == 0) ? " x" + m[n - 2] : "";
                            String what = "item #" + item + qty + " (bag " + slot / 12 + ", slot " + slot % 12 + ")";
                            if (sub == 0x0c) log(ENVOYE, "You ask " + who + " the price of " + what, null);
                            else log(ENVOYE, "You sell " + what + " to " + who,
                                    new Object[]{"item_sold", "", "item #" + item, "", "", "", who});
                            break;
                        }
                        case 0x07: case 0x09: {
                            long art = i < m.length ? TwomDecoder.varint(m, i, false)[0] : -1;
                            if (sub == 0x07) log(ENVOYE, "You select article #" + art + " (" + who + ")", null);
                            else log(ENVOYE, "You buy article #" + art + " from " + who,
                                    new Object[]{"item_bought", "", "article #" + art, "", "", "", who});
                            break;
                        }
                        default:
                            log(ENVOYE, String.format(Locale.US, "NPC action 0x%02x (%s)", sub, who), null);
                    }
                    break;
                }
                case 0xa4: {
                    // [a4][0d][ton ID][00][sac][case][numero de l'objet]... : tu utilises un objet
                    if (m.length >= 12 && m[1] == 0x0d) {
                        int bag = m[7] & 0xff, slot = m[8] & 0xff;
                        long item = TwomDecoder.varint(m, 9, false)[0];
                        if (showSent) log(ENVOYE, "You use item #" + item + " (bag " + bag + ", slot " + slot + ")",
                                new Object[]{"item_used", "", "item #" + item, "", "", "", "bag " + bag + " slot " + slot});
                    }
                    break;
                }
                case 0x67:
                    // arrivee sur la map : le chargement se termine dans les secondes qui suivent
                    synchronized (this) {
                        if (loading) { loading = false; log(SYSTEME, "Map loaded", null); }
                        loadingUntil = now() + 4000;
                    }
                    break;
                case 0x9e: {
                    // [9e][canal][longueur][texte UTF-16] : ce que tu ecris dans le chat
                    if (!showChat || !showSent) break;
                    int len = m[2] & 0xff;
                    String txt = new String(m, 3, Math.min(len, m.length - 3), java.nio.charset.StandardCharsets.UTF_16LE);
                    int ch = m[1] & 0xff;
                    log(ENVOYE, "You say" + (ch != 1 ? " (channel " + ch + ")" : "") + ": " + txt, null);
                    break;
                }
                case 0x7e:
                    startLoading();
                    if (showSent && verbose) log(ENVOYE, "Client version sent", null);
                    break;
                case 0x68:
                    if (showSent && verbose) log(ENVOYE, "Ping", null);
                    break;
                case 0xa8:
                    if (showSent && verbose) log(ENVOYE, "UI action (a8 " + TwomDecoder.hex(m, 1, 3) + ")", null);
                    break;
                default:
                    if (showSent && verbose) log(ENVOYE, String.format(Locale.US, "Message 0x%02x (%d bytes)", op, m.length), null);
            }
        } catch (RuntimeException ignored) {
            // message tronque : ignore
        }
    }

    /** un message clair recu du serveur */
    public void message(byte[] m) {
        if (m.length == 0) return;
        int op = m[0] & 0xff;
        try {
            switch (op) {
                case 0xd6: case 0x46: apparition(m, op); break;
                case 0x43: deplacement(m); break;
                case 0x4d: attaque(m); break;
                case 0x49: mort(m); break;
                case 0x47: retrait(m); break;
                case 0x86:
                    // [86][01][modele de l'objet]... : un objet arrive dans ton inventaire
                    if (m.length >= 5 && m[1] == 1) {
                        long item = TwomDecoder.varint(m, 2, false)[0];
                        log(DROP, "You obtained item #" + item, new Object[]{"item_obtained", "", "item #" + item, "", "", "", ""});
                    }
                    break;
                case 0x87: {
                    // [87][sac][case] puis l'objet de la case (numero... quantite, 00), ou que des zeros
                    if (m.length >= 4) {
                        int bag = m[1] & 0xff, slot = m[2] & 0xff;
                        boolean empty = true;
                        for (int k = 3; k < m.length; k++) if (m[k] != 0) { empty = false; break; }
                        if (empty) {
                            log(RECU, "Inventory bag " + bag + ", slot " + slot + ": now empty", null);
                        } else {
                            long item = TwomDecoder.varint(m, 3, false)[0];
                            int n = m.length;
                            String qty = (m[n - 1] == 0 && (m[n - 2] & 0x80) == 0) ? " x" + m[n - 2] : "";
                            log(RECU, "Inventory bag " + bag + ", slot " + slot + ": item #" + item + qty, null);
                        }
                    }
                    break;
                }
                case 0x8c: {
                    // [8c][varint signe] : variation de ton or (or ramasse, vente, achat, teleportation payante)
                    long g = TwomDecoder.varint(m, 1, true)[0];
                    log(RECU, "Gold " + (g >= 0 ? "+" : "") + g, new Object[]{"gold", "", "", "", "", "", g});
                    break;
                }
                case 0x91: {
                    // [91][action][PNJ][ton ID]... : reponse d'un PNJ
                    int sub = m.length > 1 ? m[1] & 0xff : -1;
                    long[] npc = TwomDecoder.varint(m, 2, false);
                    int i = (int) npc[1] + 4;
                    if (sub == 0x0d && i < m.length) {
                        log(RECU, "NPC #" + npc[0] + " offers " + TwomDecoder.varint(m, i, false)[0] + " gold", null);
                    } else if (sub == 0x10) {
                        log(RECU, "Sale to NPC #" + npc[0] + " completed", null);
                    } else if (sub == 0x0b) {
                        log(RECU, "Purchase from NPC #" + npc[0] + " completed", null);
                    } else if (sub == 0x01 && verbose) {
                        String[] c = TwomDecoder.parseChat(m);
                        if (c != null) log(RECU, "NPC #" + npc[0] + " dialog: " + (c[0].isEmpty() ? c[1] : c[0]), null);
                    }
                    break;
                }
                case 0x62:
                    // [62 00 00][id] : identifiant de ton personnage, envoye a la connexion
                    if (m.length == 7 && m[1] == 0 && m[2] == 0) {
                        synchronized (this) { selfId = TwomDecoder.hex(m, 3, 4); }
                    }
                    break;
                case 0xa0:
                    if (showChat) {
                        String[] c = TwomDecoder.parseChat(m);
                        if (c == null) break;
                        if (c[0].startsWith("TXT_ENCHANT_SUCCESS")) log(RECU, c[1] + " enchanted an item successfully", null);
                        else if (!c[0].startsWith("TXT_") && !c[1].startsWith("TXT_"))
                            log(RECU, c[0].isEmpty() ? c[1] : c[0] + ": " + c[1], null);
                    }
                    break;
                default:
            }
        } catch (RuntimeException ignored) {
            // message tronque ou format inattendu : on l'ignore
        }
    }

    // ------------------------------------------------------------ drops
    /** morts recentes, pour savoir de quel mob vient un drop */
    private static final class Death {
        final String name; final long x, y, t;
        Death(String name, long x, long y, long t) { this.name = name; this.x = x; this.y = y; this.t = t; }
    }

    private final java.util.ArrayDeque<Death> recentDeaths = new java.util.ArrayDeque<>();

    /**
     * " (from Yeti)" si un mob est mort juste avant pres de cet endroit ; sinon "".
     * Le serveur envoie les drops juste apres la mort : on prend la mort la plus recente
     * (et non la plus proche, deux mobs pouvant mourir presque au meme endroit).
     */
    private synchronized String dropSource(long x, long y) {
        long now = System.currentTimeMillis();
        java.util.Iterator<Death> it = recentDeaths.descendingIterator();
        while (it.hasNext()) {
            Death d = it.next();
            if (now - d.t > 4000) break;
            if (Math.hypot(d.x - x, d.y - y) <= 80) return " (from " + d.name + ")";
        }
        return "";
    }

    /**
     * Un objet tombe au sol : apparition (0x46) sans nom, avec un numero de modele sur 3 octets.
     * [46][id][x][y][4 champs][modele] ; le nom de l'objet n'est pas envoye (le jeu le connait).
     */
    private void itemDrop(byte[] m) {
        String id = TwomDecoder.hex(m, 1, 4);
        int i = 5;
        long[] xv = TwomDecoder.varint(m, i, true); i = (int) xv[1];
        long[] yv = TwomDecoder.varint(m, i, true); i = (int) yv[1];
        for (int k = 0; k < 4; k++) i = (int) TwomDecoder.varint(m, i, true)[1];
        int start = i;
        long[] tv = TwomDecoder.varint(m, i, false);
        if (tv[1] - start != 3) return;                  // pas un objet (mob, familier, effet...)
        TwomDecoder.Entity e = new TwomDecoder.Entity();
        e.id = id;
        e.x = (int) xv[0];
        e.y = (int) yv[0];
        e.name = "item #" + tv[0];
        synchronized (this) { ents.put(id, e); }
        log(DROP, e.name + " dropped at (" + e.x + "," + e.y + ")" + dropSource(e.x, e.y),
                new Object[]{"item_dropped", id, e.name, "", e.x, e.y, dropSource(e.x, e.y).replace(" (from ", "").replace(")", "")});
    }

    private void apparition(byte[] m, int op) {
        boolean changed = false;
        List<TwomDecoder.Entity> parsed = TwomDecoder.parseEntities(m, op == 0xd6 ? 2 : 1);
        if (op == 0x46 && m.length > 14) {
            String sid = TwomDecoder.hex(m, 1, 4);
            boolean named = false;
            for (TwomDecoder.Entity e : parsed) if (e.id.equals(sid)) named = true;
            if (!named) itemDrop(m);
        }
        for (TwomDecoder.Entity e : parsed) {
            boolean isNew;
            synchronized (this) {
                ents.put(e.id, e);
                if (!e.name.endsWith("Gold")) inView.add(e.id);
                isNew = e.lvl != null && !mobs.containsKey(e.id);
                if (e.lvl != null) mobs.put(e.id, e);
            }
            if (e.lvl != null) {
                changed = true;
                if (isNew) {
                    boolean spawn = op == 0x46;
                    log(RECU, e.name + " (lvl " + e.lvl + ") " + (spawn ? "spawned" : "in view") + " at (" + e.x + "," + e.y + ")",
                            new Object[]{spawn ? "spawn" : "in_view", e.id, e.name, e.lvl, e.x, e.y, ""});
                }
            } else if (e.name.endsWith("Gold")) {
                String src = dropSource(e.x, e.y);
                log(DROP, e.name + " dropped at (" + e.x + "," + e.y + ")" + src,
                        new Object[]{"gold_dropped", e.id, e.name, "", e.x, e.y, src.replace(" (from ", "").replace(")", "")});
            } else if (verbose) {
                log(RECU, e.name + " in view at (" + e.x + "," + e.y + ")", null);
            }
            checkWatch(e, op == 0x46);
        }
        if (changed) mobsChanged();
    }

    private void deplacement(byte[] m) {
        int i = 1;
        i = (int) TwomDecoder.varint(m, i, true)[1];
        long[] xv = TwomDecoder.varint(m, i, true);
        int x = (int) xv[0];
        i = (int) xv[1];
        i = (int) TwomDecoder.varint(m, i, true)[1];
        String id = TwomDecoder.hex(m, i, 4);
        i += 4;
        int y = (int) TwomDecoder.varint(m, i, true)[0];
        TwomDecoder.Entity mob;
        synchronized (this) {
            TwomDecoder.Entity e = ents.get(id);
            if (e != null) { e.x = x; e.y = y; }
            if (id.equals(selfId)) setSelf(x, y);
            mob = mobs.get(id);
            if (mob != null) { mob.x = x; mob.y = y; }
        }
        if (mob != null) {
            if (verbose) log(RECU, mob.name + " moves to (" + x + "," + y + ")", null);
            mobsChanged();
        }
    }

    private void attaque(byte[] m) {
        String att = TwomDecoder.hex(m, 2, 4);
        int n = m[6] & 0xff, i = 7;
        for (int j = 0; j < n; j++) {
            String tgt = TwomDecoder.hex(m, i, 4);
            i += 6;
            long[] d = TwomDecoder.varint(m, i, false);
            i = (int) d[1];
            TwomDecoder.Entity mob;
            String who;
            synchronized (this) {
                mob = mobs.get(tgt);
                who = nom(att);
                if (mob != null) hits.computeIfAbsent(tgt, k -> new LinkedHashMap<>()).merge(who, 1, Integer::sum);
            }
            if (mob != null && verbose) log(RECU, ("you".equals(who) ? "You hit " : who + " hits ") + mob.name + " (" + d[0] + ")", null);
        }
    }

    private void mort(byte[] m) {
        String vic = TwomDecoder.hex(m, 1, 4);
        long[] xv = TwomDecoder.varint(m, 5, true);
        long[] yv = TwomDecoder.varint(m, (int) xv[1], true);
        String killer, name;
        Integer lvl;
        int nb = 0;
        synchronized (this) {
            killer = nom(TwomDecoder.hex(m, m.length - 4, 4));
            TwomDecoder.Entity e = mobs.containsKey(vic) ? mobs.get(vic) : ents.get(vic);
            name = e != null ? e.name : "?" + vic;
            lvl = e != null ? e.lvl : null;
            Map<String, Integer> h = hits.remove(vic);
            if (h != null) for (int c : h.values()) nb += c;
            if (mobs.remove(vic) != null) deaths++;
            alerted.remove(vic);
            inView.remove(vic);
        }
        String detail = "killed by " + killer + (nb > 0 ? " (" + nb + (nb == 1 ? " hit)" : " hits)") : "");
        synchronized (this) {
            recentDeaths.addLast(new Death(name, xv[0], yv[0], System.currentTimeMillis()));
            while (recentDeaths.size() > 30) recentDeaths.pollFirst();
        }
        log(RECU, name + " died at (" + xv[0] + "," + yv[0] + ") - " + detail,
                new Object[]{"death", vic, name, lvl, xv[0], yv[0], detail});
        mobsChanged();
    }

    private void retrait(byte[] m) {
        String id = TwomDecoder.hex(m, 1, 4);
        TwomDecoder.Entity mob, e;
        synchronized (this) {
            mob = mobs.remove(id);
            alerted.remove(id);
            inView.remove(id);
            hits.remove(id);
            e = ents.get(id);
        }
        if (mob != null) {
            log(RECU, mob.name + " left view at (" + mob.x + "," + mob.y + ")",
                    new Object[]{"out_of_view", id, mob.name, mob.lvl, mob.x, mob.y, ""});
            mobsChanged();
        } else if (e != null && e.name.endsWith("Gold")) {
            log(DROP, e.name + " picked up", new Object[]{"gold_picked_up", id, e.name, "", e.x, e.y, ""});
        } else if (e != null && e.name.startsWith("item #")) {
            log(DROP, e.name + " picked up", new Object[]{"item_picked_up", id, e.name, "", e.x, e.y, ""});
        }
    }

    /**
     * Lecture d'une connexion TCP de TWOM. On ne sait pas d'avance quel port utilise le serveur
     * de jeu : on regarde les premieres trames, et on ne decode la suite que si elles ont la
     * forme des messages du jeu. Les autres connexions (HTTPS, etc.) sont ignorees.
     */
    public static final class Stream {
        static final boolean[] FIRST_OPS = new boolean[256];
        static {
            for (int op : new int[]{0x0a, 0x23, 0x2b, 0x43, 0x45, 0x46, 0x47, 0x49, 0x4d, 0x5b, 0x5e, 0x62,
                    0x6a, 0x6c, 0x7a, 0xa0, 0xa4, 0xa9, 0xaa, 0xbc, 0xd6}) FIRST_OPS[op] = true;
        }

        private final TwomDecoder.FrameReader reader = new TwomDecoder.FrameReader();
        private final TwomDecoder.FrameReader clientReader = new TwomDecoder.FrameReader();
        private final GameState gs;
        private final String dest;
        private final List<byte[]> pending = new ArrayList<>();
        private final List<byte[]> pendingClient = new ArrayList<>();
        private int state = 0;          // 0 = a verifier, 1 = serveur de jeu, -1 = autre connexion
        private int good = 0, bad = 0;

        public Stream(GameState gs) { this(gs, "?"); }

        public Stream(GameState gs, String dest) { this.gs = gs; this.dest = dest; }

        public boolean isGame() { return state == 1; }

        /** donnees envoyees par le telephone sur cette connexion */
        public synchronized void feedClient(byte[] data, int off, int len) {
            if (state < 0) return;
            for (byte[] frame : clientReader.feed(data, off, len)) {
                if (frame.length <= 2) continue;            // battement de coeur
                if (state == 1) client(frame);
                else if (pendingClient.size() < 200) pendingClient.add(frame);
            }
        }

        public synchronized void feed(byte[] data, int off, int len) {
            if (state < 0) return;
            for (byte[] frame : reader.feed(data, off, len)) {
                if (state == 1) { process(frame); continue; }
                pending.add(frame);
                if (frame.length > 2) {
                    List<byte[]> m = TwomDecoder.decodeFrame(frame, gs.knownIds());
                    if (!m.isEmpty() && m.get(0).length > 0 && FIRST_OPS[m.get(0)[0] & 0xff]) good++;
                    else bad++;
                }
                if (bad > 3 || pending.size() > 60) {
                    state = -1;
                    pending.clear();
                    pendingClient.clear();
                    gs.debug("Connection " + dest + ": not the game server");
                    return;
                }
                if (good >= 5) {
                    state = 1;
                    gs.connection(true, dest);
                    for (byte[] p : pending) process(p);
                    pending.clear();
                    for (byte[] p : pendingClient) client(p);
                    pendingClient.clear();
                }
            }
        }

        private void client(byte[] frame) {
            byte[] m = TwomDecoder.decodeClientFrame(frame, gs.selfIdHex());
            gs.rawClient(m, frame);
            if (m == null) gs.clientUndecoded(frame);
            else gs.clientMessage(m);
        }

        private void process(byte[] frame) {
            for (byte[] msg : TwomDecoder.decodeFrame(frame, gs.knownIds())) {
                gs.rawServer(msg);
                gs.message(msg);
            }
        }

        public synchronized void closed() {
            if (state == 1) {
                gs.connection(false, dest);
                gs.clearView();
            }
        }
    }
}
