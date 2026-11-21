package fr.twomlive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le tableau a remplir : ce que le protocole ne nomme pas (competences, stats, buffs, maps, objets, PNJ).
 * Chaque numero vu en jeu y est ajoute automatiquement ; le joueur lui donne un nom, et pour les
 * competences leur cout en cristaux et en mana. Les noms sont ensuite utilises dans le log.
 */
public final class Names {

    public enum Cat {
        SKILL("Skills", "skill"), STAT("Stats", "stat"), BUFF("Buffs", "buff"),
        MAP("Maps", "map"), ITEM("Items", "item"), NPC("NPCs", "NPC"), BAG("Bags", "bag"),
        CLASS("Classes", "class"), FACTION("Factions", "side"), STATUS("PK status", "PK code");

        public final String title, word;

        Cat(String title, String word) { this.title = title; this.word = word; }
    }

    public static final class Entry {
        public final Cat cat;
        public final String id;
        public String name = "";
        public double crystals = 0;
        public int mana = 0;
        public int slots = 0;         // sacs : nombre de places
        public String last = "";      // derniere valeur vue (stats, duree des buffs...)
        public String note = "";
        public int seen = 0;          // nombre de fois vu depuis le lancement

        Entry(Cat cat, String id) { this.cat = cat; this.id = id; }

        public boolean named() { return !name.trim().isEmpty(); }
    }

    private final Map<String, Entry> map = new LinkedHashMap<>();
    /** appele quand le tableau change (nouvelle ligne ou modification) : pour l'enregistrer */
    public volatile Runnable onChange;
    /** appele quand une nouvelle ligne apparait : pour rafraichir l'ecran */
    public volatile Runnable onNew;

    public Names() { defaults(); }

    private static String key(Cat c, String id) { return c.name() + ":" + id; }

    public synchronized Entry get(Cat c, String id) { return map.get(key(c, id)); }

    private Entry create(Cat c, String id) {
        Entry e = map.get(key(c, id));
        if (e == null) {
            e = new Entry(c, id);
            map.put(key(c, id), e);
        }
        return e;
    }

    /** un numero vient d'etre vu en jeu ; last peut etre null */
    public void see(Cat c, String id, String last) {
        boolean added;
        synchronized (this) {
            Entry e = map.get(key(c, id));
            added = e == null;
            if (added) e = create(c, id);
            e.seen++;
            if (last != null) e.last = last;
        }
        if (added) {
            fire(onChange);
            fire(onNew);
        }
    }

    public void see(Cat c, long id, String last) { see(c, String.valueOf(id), last); }

    private static void fire(Runnable r) { if (r != null) r.run(); }

    /** modification faite par le joueur dans le tableau */
    public void edited() { fire(onChange); }

    /** "Teleport Scroll (item #130005)" ou "item #130005" */
    public synchronized String label(Cat c, String id) {
        Entry e = map.get(key(c, id));
        String num = c.word + " #" + id;
        return e != null && e.named() ? e.name.trim() + " (" + num + ")" : num;
    }

    public String label(Cat c, long id) { return label(c, String.valueOf(id)); }

    /** pareil, avec une majuscule au debut (debut de phrase) */
    public String Label(Cat c, long id) {
        String s = label(c, id);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public synchronized String nameOrNull(Cat c, String id) {
        Entry e = map.get(key(c, id));
        return e != null && e.named() ? e.name.trim() : null;
    }

    /** cout d'une competence en sixiemes de cristal */
    public synchronized int crystalSixths(int skill) {
        Entry e = map.get(key(Cat.SKILL, String.valueOf(skill)));
        return e == null ? 0 : (int) Math.round(e.crystals * 6);
    }

    public synchronized void setCrystals(int skill, double c) { create(Cat.SKILL, String.valueOf(skill)).crystals = c; }

    /** nombre de places d'un sac (0 si inconnu) */
    public synchronized int bagSlots(int bagItem) {
        Entry e = map.get(key(Cat.BAG, String.valueOf(bagItem)));
        return e == null ? 0 : e.slots;
    }

    /** lignes d'une categorie, par numero croissant */
    public synchronized List<Entry> list(Cat c) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : map.values()) if (e.cat == c) out.add(e);
        out.sort((a, b) -> compareIds(a.id, b.id));
        return out;
    }

    public synchronized int unnamed(Cat c) {
        int n = 0;
        for (Entry e : map.values()) if (e.cat == c && !e.named()) n++;
        return n;
    }

    static int compareIds(String a, String b) {
        String[] x = a.split("/"), y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            try { c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i])); } catch (NumberFormatException e) { c = x[i].compareTo(y[i]); }
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    // ------------------------------------------------------------ enregistrement
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : map.values()) {
            sb.append(e.cat.name()).append('\t').append(esc(e.id)).append('\t').append(esc(e.name)).append('\t')
                    .append(e.crystals).append('\t').append(e.mana).append('\t').append(esc(e.last)).append('\t')
                    .append(esc(e.note)).append('\t').append(e.slots).append('\n');
        }
        return sb.toString();
    }

    /** recharge ce qui a ete enregistre, par-dessus les valeurs par defaut */
    public synchronized void load(String s) { load(s, java.util.Collections.emptySet()); }

    /** pareil, sans recharger certaines categories (quand leur sens a change d'une version a l'autre) */
    public synchronized void load(String s, java.util.Set<Cat> skip) {
        if (s == null) return;
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            Cat c;
            try { c = Cat.valueOf(f[0]); } catch (IllegalArgumentException ex) { continue; }
            if (skip.contains(c)) continue;
            Entry e = create(c, unesc(f[1]));
            e.name = unesc(f[2]);
            try { e.crystals = Double.parseDouble(f[3]); } catch (NumberFormatException ignored) { }
            try { e.mana = Integer.parseInt(f[4]); } catch (NumberFormatException ignored) { }
            e.last = unesc(f[5]);
            e.note = unesc(f[6]);
            if (f.length > 7) try { e.slots = Integer.parseInt(f[7]); } catch (NumberFormatException ignored) { }
        }
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** le tableau entier en CSV, pour le partager */
    public synchronized String csv() {
        StringBuilder sb = new StringBuilder("category,id,name,crystals,mana,slots,last value,note\n");
        for (Cat c : Cat.values()) {
            for (Entry e : list(c)) {
                sb.append(c.title).append(',').append(csvCell(e.id)).append(',').append(csvCell(e.name)).append(',')
                        .append(c == Cat.SKILL ? fmt(e.crystals) : "").append(',')
                        .append(c == Cat.SKILL ? String.valueOf(e.mana) : "").append(',')
                        .append(c == Cat.BAG ? String.valueOf(e.slots) : "").append(',')
                        .append(csvCell(e.last)).append(',').append(csvCell(e.note)).append('\n');
            }
        }
        return sb.toString();
    }

    public static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }

    // ------------------------------------------------------------ ce qu'on sait deja (captures)
    private void def(Cat c, String id, String name, String note) {
        Entry e = create(c, id);
        e.name = name;
        e.note = note;
    }

    private void defaults() {
        def(Cat.SKILL, "7", "", "uses 2 crystals");
        setCrystals(7, 2);
        def(Cat.SKILL, "11", "", "critical chance up for a while (?)");
        setCrystals(11, 3);
        def(Cat.SKILL, "12", "", "area attacks for a few seconds (?)");
        setCrystals(12, 2);

        def(Cat.STAT, "0/0", "HP", "933 at full life");
        def(Cat.STAT, "1/1", "", "9");
        def(Cat.STAT, "1/2", "Move speed", "83; 130 with the speed skill");
        def(Cat.STAT, "2/0", "EXP", "total experience");
        def(Cat.STAT, "2/4", "MP", "164 at full mana");
        def(Cat.STAT, "3/0", "", "76; 65 without the helm");
        def(Cat.STAT, "3/1", "", "28; 6 without the weapon, 5 without the helm too");
        def(Cat.STAT, "3/13", "", "4");
        def(Cat.STAT, "3/15", "", "31; -4 for each item removed");
        def(Cat.STAT, "3/22", "", "593; 537 without the weapon");
        def(Cat.STAT, "3/23", "", "320; 5820 with the critical skill: crit rate in 1/100 %?");
        def(Cat.STAT, "3/24", "", "199");

        def(Cat.BUFF, "2", "", "speed skill, 7 s");
        def(Cat.BUFF, "5", "", "critical skill, 6 s");
        def(Cat.BUFF, "32", "", "consumable, 20 min");
        def(Cat.BUFF, "132", "", "after each kill, 1 min, stacks up to 5");

        def(Cat.MAP, "4", "", "where the teleporter NPC sent you for 495 gold");
        def(Cat.MAP, "5", "Wingfield Beach", "");
        def(Cat.MAP, "6", "", "teleport scroll destination: starting village?");
        def(Cat.MAP, "7", "", "next to the village");
        def(Cat.MAP, "17", "", "Yeti Highguard, Enraged Wings");
        def(Cat.MAP, "25", "Forest of Grave", "");

        def(Cat.ITEM, "130005", "", "teleport scroll");
        def(Cat.ITEM, "30010", "", "your weapon");
        def(Cat.ITEM, "40029", "", "your helm");

        // sacs : nombre de places devine d'apres la plus haute case vue ; a corriger si besoin
        def(Cat.BAG, "120012", "", "guess: 7 slots (highest slot seen)");
        create(Cat.BAG, "120012").slots = 7;
        def(Cat.BAG, "120022", "", "guess: 7 slots (highest slot seen)");
        create(Cat.BAG, "120022").slots = 7;
        def(Cat.BAG, "120028", "", "guess: 8 slots (highest slot seen)");
        create(Cat.BAG, "120028").slots = 8;
        def(Cat.BAG, "120074", "", "guess: 8 slots (highest slot seen)");
        create(Cat.BAG, "120074").slots = 8;

        def(Cat.CLASS, "1", "Warrior", "Usup, Gildart");
        def(Cat.CLASS, "2", "", "Lekz, Bayyy, Shotguns, EvilArmy...");
        def(Cat.CLASS, "3", "Mage", "Jiem");
        def(Cat.CLASS, "4", "", "4dd, SKG, Croagunk...");

        // relatif a toi : 6 = faction adverse ; au village, tout le monde est a 5
        def(Cat.FACTION, "5", "Ally", "your faction (Siras: EvilArmy, T3ss3ract...), or anyone in the village");
        def(Cat.FACTION, "6", "Enemy", "other faction (Lanos: Lekz, Bayyy, Zoe887, KilerBee)");

        def(Cat.STATUS, "0", "White PK", "has not killed anyone");
        def(Cat.STATUS, "1", "Purple PK", "guess: attacked a player without killing");
        def(Cat.STATUS, "2", "Red PK", "killed someone in the last hour (EvilArmy)");
        def(Cat.STATUS, "3", "Black PK", "red PK who died since (Lekz, Bayyy, Zoe887)");

        def(Cat.NPC, "1158", "", "village: sells, article #20 = 110 gold");
        def(Cat.NPC, "1170", "", "village: buys resources");
        def(Cat.NPC, "1178", "", "teleporter (495 gold)");
    }
}
