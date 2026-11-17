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
        void onEvent(String line);
        void onMobsChanged();
    }

    public static final GameState INSTANCE = new GameState();

    public volatile boolean showChat = true;
    public volatile boolean verbose = false;

    private final Map<String, TwomDecoder.Entity> ents = new HashMap<>();
    private final Map<String, TwomDecoder.Entity> mobs = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> hits = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("heure;evenement;id;nom;niveau;x;y;detail\n");
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    public int deaths = 0;

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized List<String> lines() { return new ArrayList<>(lines); }
    public synchronized String csv() { return csv.toString(); }
    public synchronized Map<String, TwomDecoder.Entity> knownIds() { return new HashMap<>(ents); }

    public synchronized void reset() {
        ents.clear(); mobs.clear(); hits.clear(); lines.clear(); deaths = 0; selfId = null;
        csv.setLength(0);
        csv.append("heure;evenement;id;nom;niveau;x;y;detail\n");
        for (Listener l : listeners) l.onMobsChanged();
    }

    public void info(String text) { log("ℹ️", text, null); }

    private String selfId = null;

    private String nom(String id) {
        if (id.equals(selfId)) return "toi";
        TwomDecoder.Entity e = ents.get(id);
        return e != null ? e.name : "?" + id;
    }

    private void log(String icon, String text, Object[] ev) {
        String h = fmt.format(new Date());
        String line = "[" + h + "] " + icon + " " + text;
        synchronized (this) {
            lines.add(line);
            if (lines.size() > 600) lines.remove(0);
            if (ev != null) {
                csv.append(h);
                for (Object o : ev) csv.append(';').append(o == null ? "" : String.valueOf(o).replace(';', ','));
                csv.append('\n');
            }
        }
        for (Listener l : listeners) l.onEvent(line);
    }

    private void mobsChanged() { for (Listener l : listeners) l.onMobsChanged(); }

    /** resume des mobs en vue, pour l'ecran principal */
    public synchronized String mobsSummary() {
        if (mobs.isEmpty()) return "Aucun mob en vue.";
        Map<String, Integer> c = new TreeMap<>();
        for (TwomDecoder.Entity m : mobs.values()) c.merge(m.name + " (niv " + m.lvl + ")", 1, Integer::sum);
        StringBuilder sb = new StringBuilder(mobs.size() + " mob(s) en vue :");
        for (Map.Entry<String, Integer> e : c.entrySet()) sb.append("\n  ").append(e.getKey()).append(" ×").append(e.getValue());
        sb.append("\n");
        List<Map.Entry<String, TwomDecoder.Entity>> l = new ArrayList<>(mobs.entrySet());
        l.sort((a, b) -> a.getValue().name.compareTo(b.getValue().name));
        for (Map.Entry<String, TwomDecoder.Entity> e : l) {
            TwomDecoder.Entity m = e.getValue();
            sb.append("\n· ").append(m.name).append(" (").append(m.x).append(',').append(m.y).append(')');
            Map<String, Integer> att = hits.get(e.getKey());
            if (att != null && !att.isEmpty()) sb.append(" — en combat avec ").append(String.join(", ", att.keySet()));
        }
        return sb.toString();
    }

    public void connection(boolean open) {
        log("🔌", open ? "Connecté au serveur de jeu" : "Connexion au serveur de jeu fermée", null);
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
                        if (c[0].startsWith("TXT_ENCHANT_SUCCESS")) log("✨", c[1] + " a réussi un enchantement", null);
                        else if (!c[0].startsWith("TXT_") && !c[1].startsWith("TXT_"))
                            log("💬", c[0].isEmpty() ? c[1] : c[0] + ": " + c[1], null);
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
                isNew = e.lvl != null && !mobs.containsKey(e.id);
                if (e.lvl != null) mobs.put(e.id, e);
            }
            if (e.lvl != null) {
                changed = true;
                if (isNew) {
                    boolean spawn = op == 0x46;
                    log(spawn ? "🟢" : "👁", e.name + " (niv " + e.lvl + ") " + (spawn ? "apparaît" : "en vue") + " en (" + e.x + "," + e.y + ")",
                            new Object[]{spawn ? "apparition" : "en_vue", e.id, e.name, e.lvl, e.x, e.y, ""});
                }
            } else if (e.name.endsWith("Gold")) {
                log("💰", e.name + " au sol en (" + e.x + "," + e.y + ")", new Object[]{"or_au_sol", e.id, e.name, "", e.x, e.y, ""});
            } else if (verbose) {
                log("🧍", e.name + " en vue en (" + e.x + "," + e.y + ")", null);
            }
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
            mob = mobs.get(id);
            if (mob != null) { mob.x = x; mob.y = y; }
        }
        if (mob != null) {
            if (verbose) log("↗", mob.name + " → (" + x + "," + y + ")", null);
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
            if (mob != null && verbose) log("⚔", who + " frappe " + mob.name + " (" + d[0] + ")", null);
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
        }
        String detail = "tué par " + killer + (nb > 0 ? " (" + nb + " coups)" : "");
        log("☠", name + " mort en (" + xv[0] + "," + yv[0] + ") — " + detail,
                new Object[]{"mort", vic, name, lvl, xv[0], yv[0], detail});
        mobsChanged();
    }

    private void retrait(byte[] m) {
        String id = TwomDecoder.hex(m, 1, 4);
        TwomDecoder.Entity mob, e;
        synchronized (this) {
            mob = mobs.remove(id);
            hits.remove(id);
            e = ents.get(id);
        }
        if (mob != null) {
            log("👋", mob.name + " sort du champ de vision (" + mob.x + "," + mob.y + ")",
                    new Object[]{"hors_vue", id, mob.name, mob.lvl, mob.x, mob.y, ""});
            mobsChanged();
        } else if (e != null && e.name.endsWith("Gold")) {
            log("🪙", e.name + " ramassé", new Object[]{"or_ramasse", id, e.name, "", e.x, e.y, ""});
        }
    }

    /** decodeur d'une connexion au serveur de jeu */
    public static final class Stream {
        private final TwomDecoder.FrameReader reader = new TwomDecoder.FrameReader();
        private final GameState gs;

        public Stream(GameState gs) { this.gs = gs; }

        public void feed(byte[] data, int off, int len) {
            for (byte[] frame : reader.feed(data, off, len)) {
                for (byte[] msg : TwomDecoder.decodeFrame(frame, gs.knownIds())) gs.message(msg);
            }
        }
    }
}
