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
        log(ALERTE, "ALERT: " + e.name + lvl + (spawned ? " spawned" : " in view") + " at (" + e.x + "," + e.y + ") - ID " + e.id,
                new Object[]{"alert", e.id, e.name, e.lvl, e.x, e.y, spawned ? "spawned" : "in view"});
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
        ents.clear(); mobs.clear(); hits.clear(); lines.clear(); alerted.clear(); inView.clear(); hasSelf = false; deaths = 0; attacksSent = 0; selfId = null;
        csv.setLength(0);
        csv.append("time;event;id;name;level;x;y;detail\n");
        for (Listener l : listeners) l.onMobsChanged();
    }

    public void info(String text) { log(SYSTEME, text, null); }

    /** messages de diagnostic, affiches seulement avec l'option "Details" */
    public void debug(String text) { if (verbose) log(SYSTEME, text, null); }

    private String selfId = null;

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
        return i + 4 <= m.length && (m[i] & 0xf8) == 0x88 && (m[i + 1] & 0x80) != 0
                && (m[i + 2] & 0x80) != 0 && (m[i + 3] & 0x80) == 0;
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
                    String tgt = (skill == 0 && isId(m, i)) ? TwomDecoder.hex(m, i, 4) : null;
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
                        log(ENVOYE, "You cast skill #" + skill + " from (" + xv[0] + "," + yv[0] + ")",
                                new Object[]{"skill_sent", "", "", "", xv[0], yv[0], "skill " + skill});
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
                case 0x7e:
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

    private void apparition(byte[] m, int op) {
        boolean changed = false;
        for (TwomDecoder.Entity e : TwomDecoder.parseEntities(m, op == 0xd6 ? 2 : 1)) {
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
                log(DROP, e.name + " dropped at (" + e.x + "," + e.y + ")", new Object[]{"gold_dropped", e.id, e.name, "", e.x, e.y, ""});
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
                if (state == 1) gs.clientMessage(TwomDecoder.decodeClientFrame(frame));
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
                    for (byte[] p : pendingClient) gs.clientMessage(TwomDecoder.decodeClientFrame(p));
                    pendingClient.clear();
                }
            }
        }

        private void process(byte[] frame) {
            for (byte[] msg : TwomDecoder.decodeFrame(frame, gs.knownIds())) gs.message(msg);
        }

        public synchronized void closed() {
            if (state == 1) {
                gs.connection(false, dest);
                gs.clearView();
            }
        }
    }
}
