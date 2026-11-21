package fr.twomlive;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DecoderTest {

    static byte[] capture() throws Exception { return resource("/capture1_s2c.bin"); }

    static byte[] resource(String name) throws Exception {
        try (InputStream in = DecoderTest.class.getResourceAsStream(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    @Test
    public void decodeLesSixMorts() throws Exception {
        byte[] d = capture();
        GameState gs = GameState.INSTANCE;
        gs.reset();
        GameState.Stream s = new GameState.Stream(gs);
        Random r = new Random(1);
        for (int i = 0; i < d.length; ) {
            int n = Math.min(d.length - i, 1 + r.nextInt(1500));
            s.feed(d, i, n);
            i += n;
        }
        assertEquals(6, gs.deaths);
        String all = String.join("\n", gs.lines());
        assertTrue(all.contains("Imp died at (3484,1505) - killed by Minxie"));
        assertTrue(all.contains("Dark Beholder died at (3452,1818) - killed by Ampsterdam"));
    }

    @Test
    public void reconnaitLeServeurDeJeuEtIgnoreLeReste() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        GameState.Stream autre = new GameState.Stream(gs, "test:443");
        Random r = new Random(7);
        byte[] bruit = new byte[200000];
        r.nextBytes(bruit);
        bruit[0] = 0x16; bruit[1] = 0x03;              // ressemble a du TLS
        autre.feed(bruit, 0, bruit.length);
        assertFalse(autre.isGame());
        assertEquals(0, gs.deaths);

        byte[] d = capture();
        GameState.Stream jeu = new GameState.Stream(gs, "test:12210");
        for (int i = 0; i < d.length; i += 700) jeu.feed(d, i, Math.min(700, d.length - i));
        assertTrue(jeu.isGame());
        assertEquals(6, gs.deaths);
    }

    @Test
    public void decodeLesEnvoisDuTelephone() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        GameState.Stream s = new GameState.Stream(gs, "test");
        byte[] srv = capture();
        s.feed(srv, 0, Math.min(srv.length, 3000));       // reconnaissance du serveur de jeu
        assertTrue(s.isGame());
        byte[] cli = resource("/capture2_c2s.bin");
        Random r = new Random(3);
        for (int i = 0; i < cli.length; ) {
            int n = Math.min(cli.length - i, 1 + r.nextInt(3000));
            s.feedClient(cli, i, n);
            i += n;
        }
        assertEquals(103, gs.attacksSent);
        long base = gs.lines().stream().filter(l -> l.contains("(basic attack)")).count();
        assertEquals(42, base);
    }

    static byte[] encrypt(byte[] plain, int k) {
        int r = TwomDecoder.rotFor(k);
        byte[] o = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            int b = plain[i] & 0xff;
            o[i] = (byte) ((((b << r) | (b >>> (8 - r))) & 0xff) ^ k);
        }
        return o;
    }

    @Test
    public void reconnaitLesAttaquesQuelQueSoitLeRemplissage() throws Exception {
        // une vraie attaque de la session de farm, dont on change le remplissage et la cle
        byte[] cli = resource("/capture2_c2s.bin");
        byte[] attack = null;
        for (int i = 0; i + 2 <= cli.length; ) {
            int L = ((cli[i] & 0xff) << 8) | (cli[i + 1] & 0xff);
            byte[] f = TwomDecoder.sub(cli, i + 2, i + L);
            if (f.length > 1000) { attack = TwomDecoder.decodeClientFrame(f); break; }
            i += L;
        }
        assertNotNull(attack);
        assertEquals(0x4f, attack[0] & 0xff);
        byte[] plain = new byte[attack.length + 1];
        System.arraycopy(attack, 0, plain, 0, attack.length);
        byte[] fill = {0x11, 0x22, 0x33, 0x44};
        for (int i = 20; i < attack.length; i++) plain[i] = fill[i % 4];   // autre remplissage
        for (int k : new int[]{0x5d, 0xa3, 0x08, 0xf7}) {
            byte[] back = TwomDecoder.decodeClientFrame(encrypt(plain, k));
            assertNotNull(back);
            assertEquals("cle " + k, 0x4f, back[0] & 0xff);
            assertArrayEquals(TwomDecoder.sub(plain, 0, 20), TwomDecoder.sub(back, 0, 20));
        }
    }

    @Test
    public void alerteQuandUnNomSurveilleApparait() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        java.util.List<String> seen = new java.util.ArrayList<>();
        GameState.AlertListener l = (e, spawned) -> seen.add(e.name + "/" + e.id + "/" + e.x + "," + e.y);
        gs.addAlertListener(l);
        try {
            gs.setWatch(" dark BEHOLDER ,  snaketail ");
            GameState.Stream s = new GameState.Stream(gs, "test");
            byte[] d = capture();
            s.feed(d, 0, d.length);
            assertFalse(seen.isEmpty());
            for (String x : seen) assertTrue(x, x.startsWith("Dark Beholder/") || x.startsWith("Snaketail Lizard/"));
            assertEquals("une alerte par entite", new java.util.HashSet<>(seen).size(), seen.size());
            assertTrue(gs.lines().stream().anyMatch(x -> x.contains("ALERT: Dark Beholder (lvl 22)") && x.contains(" - ID ")));
            gs.setWatch("");
            gs.reset();
            seen.clear();
            s = new GameState.Stream(gs, "test");
            s.feed(d, 0, d.length);
            assertTrue(seen.isEmpty());
        } finally {
            gs.removeAlertListener(l);
            gs.setWatch("");
        }
    }

    @Test
    public void positionDuPersonnage() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        GameState.Stream s = new GameState.Stream(gs, "test");
        byte[] d = capture();
        s.feed(d, 0, Math.min(d.length, 6000));
        assertTrue(s.isGame());
        assertNull(gs.self());
        byte[] cli = resource("/capture2_c2s.bin");
        s.feedClient(cli, 0, Math.min(cli.length, 4000));
        assertNotNull(gs.self());
        s.closed();
        assertNull(gs.self());
    }

    /** cle d'une trame serveur, comme le decodeur la trouve */
    static int serverKey(byte[] p) {
        int k0 = TwomDecoder.guessKey(p);
        byte[] x = TwomDecoder.dec(p, k0);
        if (x[0] == 0x23 && TwomDecoder.splitBatch(TwomDecoder.sub(x, 0, x.length - 1)) != null) return k0;
        for (int k = 0; k < 256; k++) {
            byte[] y = TwomDecoder.dec(p, k);
            if (y[0] == 0x23 && TwomDecoder.splitBatch(TwomDecoder.sub(y, 0, y.length - 1)) != null) return k;
        }
        return k0;
    }

    /** meme capture, mais avec des identifiants d'entite qui commencent par 0x91 au lieu de 0x8b */
    static byte[] shiftIds(byte[] stream) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i + 2 <= stream.length; ) {
            int L = ((stream[i] & 0xff) << 8) | (stream[i + 1] & 0xff);
            byte[] p = TwomDecoder.sub(stream, i + 2, i + L);
            i += L;
            if (p.length > 2) {
                int k = serverKey(p);
                byte[] x = TwomDecoder.dec(p, k);
                for (int j = 0; j + 4 <= x.length - 1; j++) {
                    int b0 = x[j] & 0xff;
                    if (b0 >= 0x88 && b0 <= 0x8f && (x[j + 1] & 0x80) != 0 && (x[j + 2] & 0x80) != 0 && (x[j + 3] & 0x80) == 0) {
                        x[j] = (byte) (b0 + 6);       // 0x8b -> 0x91
                        j += 3;
                    }
                }
                p = encrypt(x, k);
            }
            o.write(L >> 8);
            o.write(L);
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    @Test
    public void identifiantsQuiOntGrandi() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        gs.setWatch("dark beholder");
        java.util.List<String> seen = new java.util.ArrayList<>();
        GameState.AlertListener l = (e, spawned) -> seen.add(e.id);
        gs.addAlertListener(l);
        try {
            byte[] d = shiftIds(capture());
            GameState.Stream s = new GameState.Stream(gs, "test");
            for (int i = 0; i < d.length; i += 900) s.feed(d, i, Math.min(900, d.length - i));
            assertTrue(s.isGame());
            assertEquals(6, gs.deaths);
            String all = String.join("\n", gs.lines());
            assertTrue(all, all.contains("Imp died at (3484,1505) - killed by Minxie"));
            assertFalse(seen.isEmpty());
            for (String id : seen) assertTrue(id, id.startsWith("91"));
        } finally {
            gs.removeAlertListener(l);
            gs.setWatch("");
        }
    }

    @Test
    public void directionDuMob() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        assertNull(gs.relative(100, 100));                 // position inconnue
        gs.setSelf(1000, 1000);
        assertEquals("300 north", gs.relative(1000, 700));
        assertEquals("300 south", gs.relative(1000, 1300));
        assertEquals("200 east", gs.relative(1200, 1000));
        assertEquals("200 west", gs.relative(800, 1000));
        assertEquals("280 north-east", gs.relative(1200, 800));
        assertEquals("280 south-west", gs.relative(800, 1200));
        assertEquals("right next to you", gs.relative(1010, 995));
        gs.reset();
    }

    @Test
    public void modeRawToutCeQuiPasse() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        java.util.List<String> file = new java.util.ArrayList<>();
        gs.rawSink = file::add;
        gs.raw = true;
        try {
            GameState.Stream s = new GameState.Stream(gs, "test");
            byte[] d = capture();
            s.feed(d, 0, d.length);
            byte[] cli = resource("/capture2_c2s.bin");
            s.feedClient(cli, 0, cli.length);
            long in = file.stream().filter(l -> l.contains(" <- ")).count();
            long out = file.stream().filter(l -> l.contains(" -> ")).count();
            assertTrue("recus " + in, in > 1000);
            assertTrue("envoyes " + out, out > 500);
            assertTrue(file.stream().anyMatch(l -> l.contains("<- 49 death (")));
            assertTrue(file.stream().anyMatch(l -> l.contains("<- d6 entities in view (")));
            assertTrue(file.stream().anyMatch(l -> l.contains("-> 43 move (")));
            // attaques : en-tete seulement, le bloc anti-triche n'est jamais recopie
            java.util.List<String> atk = new java.util.ArrayList<>();
            for (String l : file) if (l.contains("-> 4f attack/skill (")) atk.add(l);
            assertEquals(103, atk.size());
            for (String l : atk) {
                assertTrue(l, l.contains("anti-cheat block omitted"));
                assertTrue(l, l.length() < 200);
            }
        } finally {
            gs.raw = false;
            gs.rawSink = null;
        }
    }

    @Test
    public void connexionMasqueeEnModeRaw() {
        GameState gs = GameState.INSTANCE;
        java.util.List<String> file = new java.util.ArrayList<>();
        gs.rawSink = file::add;
        gs.raw = true;
        try {
            byte[] login = "\u0027Android/secret-token-123".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            gs.rawClient(login, login);
            assertEquals(1, file.size());
            assertTrue(file.get(0), file.get(0).contains("-> 27 login") && file.get(0).contains("content hidden"));
            assertFalse(file.get(0), file.get(0).contains(GameState.spacedHex(login, 1, 4)));
        } finally {
            gs.raw = false;
            gs.rawSink = null;
        }
    }

    @Test
    public void competenceCibleeEtExperience() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        // en-tete reel du fichier Raw : competence 3 sur 95f9e243, puis remplissage repete
        byte[] head = {0x4f, 0x00, 0x01, (byte) 0x8b, 0x49, 0x00, 0x00, (byte) 0x91, 0x38, 0x0a, 0x03,
                (byte) 0x95, (byte) 0xf9, (byte) 0xe2, 0x43};
        byte[] m = new byte[2062];
        System.arraycopy(head, 0, m, 0, head.length);
        byte[] fill = {0x75, 0x3b, (byte) 0xe3, 0x3f};
        for (int i = head.length; i < m.length; i++) m[i] = fill[(i - head.length) % 4];
        gs.clientMessage(m);
        // meme competence sans cible : le remplissage suit directement, meme s'il ressemble a un ID
        byte[] m2 = m.clone();
        byte[] fakeId = {(byte) 0x95, (byte) 0xa1, (byte) 0xb2, 0x33};
        for (int i = 11; i < m2.length; i++) m2[i] = fakeId[(i - 11) % 4];
        gs.clientMessage(m2);
        gs.message(new byte[]{(byte) 0x8c, (byte) 0x80, 0x4e});
        java.util.List<String> l = gs.lines();
        assertTrue(l.toString(), l.get(0).contains("You cast skill #3 on ?95f9e243"));
        assertTrue(l.toString(), l.get(1).contains("You cast skill #3 from") && !l.get(1).contains(" on "));
        assertTrue(l.toString(), l.get(2).endsWith("Gold +78"));
    }

    @Test
    public void dropsDesMobs() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        String raw = new String(resource("/raw_drops.txt"), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(" <- \\S\\S .*?\\(\\d+ B\\)  ([0-9a-f ]+)$");
        for (String line : raw.split("\n")) {
            java.util.regex.Matcher mm = p.matcher(line);
            if (!mm.find()) continue;
            String[] hx = mm.group(1).trim().split(" ");
            byte[] m = new byte[hx.length];
            for (int i = 0; i < hx.length; i++) m[i] = (byte) Integer.parseInt(hx[i], 16);
            gs.message(m);
        }
        String all = String.join("\n", gs.lines());
        assertTrue(all, all.contains("Wing Elemental died at (433,2766) - killed by you"));
        assertTrue(all, all.contains("item #150199 dropped at (433,2740) (from Wing Elemental)"));
        assertTrue(all, all.contains("59Gold dropped at (417,2756) (from Wing Elemental)"));
        assertTrue(all, all.contains("You obtained item #150199"));
        assertTrue(all, all.contains("item #150199 picked up"));
        assertEquals(all, 2, all.split("item #150198 dropped at .{0,20}\\(from Yeti\\)", -1).length - 1);
        assertEquals(all, 2, all.split("You obtained item #150198", -1).length - 1);
        assertTrue(all, all.contains("49Gold dropped at") && all.contains("47Gold dropped at"));
        // le Crystal ne laisse rien
        String afterCrystal = all.substring(all.indexOf("Crystal died at"));
        assertFalse(afterCrystal, afterCrystal.contains("dropped at"));
    }

    static byte[] hexBytes(String h) {
        String[] p = h.trim().split(" ");
        byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) Integer.parseInt(p[i], 16);
        return o;
    }

    @Test
    public void utilisationDObjets() {
        // trames reelles envoyees en utilisant des consommables (fichier Raw du 20/11)
        String[][] frames = {
            {"e9 ba 8d 8d dd 5e a0 a4 a6 af 4f 6c a0 a0 aa f8 ae fa b2 fc a0 fe 88 c0 a0 a2 a0 c2", "130022", "bag 2, slot 3"},
            {"48 1b 2c 2c 7c ff 01 07 05 10 04 13 01 01 03 d1 fc 05 01 62", "131337", "bag 3, slot 2"},
            {"71 22 15 15 45 c6 38 3e 32 37 c9 12 38 38 30 00 79 02 d9 04 98 06 c8 2c 38 65", "130069", "bag 3, slot 5"},
            {"05 d1 1c 1c 08 e8 57 d6 55 94 ac ee 57 57 d5 41 56 c1 d0 40 57 c0 d0 4f 57 51 57 f4", "130035", "bag 3, slot 4"}};
        GameState gs = GameState.INSTANCE;
        gs.reset();
        for (String[] f : frames) {
            byte[] m = TwomDecoder.decodeClientFrame(hexBytes(f[0]));
            assertNotNull(f[0], m);
            assertEquals(0xa4, m[0] & 0xff);
            gs.clientMessage(m);
        }
        java.util.List<String> l = gs.lines();
        for (int i = 0; i < frames.length; i++)
            assertTrue(l.get(i), l.get(i).endsWith("You use item #" + frames[i][1] + " (" + frames[i][2] + ")"));
        // la 3e utilisation avait ete lue comme "0x69" : quelle que soit la cle, on doit retrouver 0xa4
        byte[] plain = hexBytes("a4 0d 96 96 be 7f 00 03 02 88 82 09 00 00 01 68 fe 01 00 5a");
        for (int k = 0; k < 256; k++) {
            byte[] back = TwomDecoder.decodeClientFrame(encrypt(plain, k));
            if (back == null) continue;
            assertEquals("cle " + k, 0xa4, back[0] & 0xff);
        }
        // reponses du serveur : case d'inventaire
        gs.message(hexBytes("87 03 05 87 f8 15 00 00 04 1c a0 1d f0 1e 50 1f 78 09 00"));
        gs.message(hexBytes("87 02 03 00 00 00 00 00 00"));
        l = gs.lines();
        assertTrue(l.toString(), l.get(4).endsWith("Inventory bag 3, slot 5: item #130069 x9"));
        assertTrue(l.toString(), l.get(5).endsWith("Inventory bag 2, slot 3: now empty"));
    }

    @Test
    public void venteAchatTeleportation() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        // trames reelles du 20/11 : vente au PNJ #1170, achat au PNJ #1158, teleporteur #1178
        String[] frames = {
            "3a 5e fb 78 27 45 3c da f3",
            "df 6c 3f fe 51 60 dc af cf dc 5f cb ed ed ed cd ed 68",
            "84 fa 98 a0 55 73 e4 8a 86 e4 94 06 c2 c2 c2 c6 c2 7a",
            "bf 2b 87 f7 1c 50 7f a3 fe",
            "14 4e 08 30 c5 e3 74 4a 02 49",
            "37 7b b4 b3 0d c9 3b fc f5 9b",
            "8f 46 0c 0b b5 71 83 4a c5 24"};
        gs.message(hexBytes("62 00 00 96 98 e5 6c"));         // ton ID dans cette session
        for (String f : frames) {
            byte[] m = TwomDecoder.decodeClientFrame(hexBytes(f), gs.selfIdHex());
            assertNotNull(f, m);
            assertEquals(f, 0x91, m[0] & 0xff);
            gs.clientMessage(m);
        }
        gs.message(hexBytes("91 0d 89 12 96 98 e5 6c 83 74 00 00 00 00 00 00"));
        gs.message(hexBytes("8c 83 74"));
        gs.message(hexBytes("8c c0 6e"));
        gs.message(hexBytes("8c c3 6f"));
        java.util.List<String> l = gs.lines();
        String all = String.join("\n", l);
        assertTrue(all, l.get(0).endsWith("You talk to NPC #1170"));
        assertTrue(all, l.get(1).endsWith("You ask NPC #1170 the price of item #150193 x1 (bag 1, slot 5)"));
        assertTrue(all, l.get(2).endsWith("You sell item #150193 x1 (bag 1, slot 5) to NPC #1170"));
        assertTrue(all, l.get(3).endsWith("You close the dialog with NPC #1170"));
        assertTrue(all, l.get(4).endsWith("You select article #20 (NPC #1158)"));
        assertTrue(all, l.get(5).endsWith("You buy article #20 from NPC #1158"));
        assertTrue(all, l.get(6).endsWith("You choose option 5 (NPC #1178)"));
        assertTrue(all, l.get(7).endsWith("NPC #1170 offers 500 gold"));
        assertTrue(all, l.get(8).endsWith("Gold +500"));
        assertTrue(all, l.get(9).endsWith("Gold -110"));
        assertTrue(all, l.get(10).endsWith("Gold -495"));
    }

    @Test
    public void chatEnvoyeEtChargementDeMap() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        // trames reelles : "hi" ecrit dans le chat, puis la serie de chargement apres une teleportation
        byte[] hi = TwomDecoder.decodeClientFrame(hexBytes("ce 3d 9d 10 1d 30 1d d2"));
        assertNotNull(hi);
        assertEquals(0x9e, hi[0] & 0xff);
        gs.clientMessage(hi);
        String[] loadFrames = {"1e ab 33 f0 83 fc 8d 08 8b 88 80", "85 32 aa 69 11 e8"};
        for (String f : loadFrames) {
            byte[] p = hexBytes(f);
            assertNull(f, TwomDecoder.decodeClientFrame(p, null));
            assertTrue(f, gs.isMapLoading(p));
            gs.clientUndecoded(p);
        }
        gs.clientMessage(hexBytes("67 96 99 8d 02"));
        java.util.List<String> l = gs.lines();
        String all = String.join("\n", l);
        assertEquals(all, 3, l.size());
        assertTrue(all, l.get(0).endsWith("You say: hi"));
        assertTrue(all, l.get(1).endsWith("Map loading..."));
        assertTrue(all, l.get(2).endsWith("Map loaded"));
    }

    @Test
    public void deplacementsCaseMoves() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        gs.showMoves = true;
        try {
            // trames reelles : une entite part vers le nord a 80/s, puis s'arrete 21 unites plus loin
            gs.message(hexBytes("43 41 87 6f 00 96 94 a3 4d 8c 0a 00 c0 50 00 00 00 00 00 00 00 00"));
            gs.message(hexBytes("43 41 87 6f 00 96 94 a3 4d 8b 75 00 00 00 00 00 00 00 00 00 00"));
            // et une autre vers l'ouest a 83/s
            gs.message(hexBytes("43 00 88 04 c0 53 96 97 8e 1b 88 28 41 00 00 00 00 00 00 00 00 00"));
        } finally {
            gs.showMoves = false;
        }
        gs.message(hexBytes("43 00 88 04 c0 53 96 97 8e 1b 88 28 41 00 00 00 00 00 00 00 00 00"));   // case decochee
        java.util.List<String> l = gs.lines();
        String all = String.join("\n", l);
        assertEquals(all, 3, l.size());
        assertTrue(all, l.get(0).endsWith("#9694a34d at (1007,1546), facing north, moving at 80/s"));
        assertTrue(all, l.get(1).contains("#9694a34d (1007,1546) -> (1007,1525), moved 21 in "));
        assertTrue(all, l.get(1).endsWith(", facing north, stopped"));
        assertTrue(all, l.get(2).endsWith("#96978e1b at (1028,1064), facing west, moving at 83/s"));
    }

    @Test
    public void enregistrementLongEtZip() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("raw").toFile();
        // petits morceaux pour le test : 1 Ko par morceau, 5 Ko au total
        RawStore st = new RawStore(new java.io.File(dir, "raw"), 1024, 5 * 1024, 0);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            String l = String.format(java.util.Locale.US, "21:41:%02d.%03d <- 43 move (22 B)  43 00 88 04 #%04d", i % 60, i, i);
            st.write(l);
            all.append(l).append('\n');
        }
        java.io.File[] segs = st.segments();
        assertTrue("plusieurs morceaux", segs.length > 3);
        assertTrue("place limitee : " + st.size(), st.size() <= 5 * 1024 + 1024 + 100);
        java.io.File zip = new java.io.File(dir, "t.zip");
        long n = st.zipTo(zip);
        String unz;
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
            java.util.zip.ZipEntry e = z.getNextEntry();
            assertEquals(RawStore.ZIP_ENTRY, e.getName());
            unz = new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertNull(z.getNextEntry());
        }
        assertEquals(n, unz.length());
        // on garde la fin de la seance, sans trou ni ligne coupee
        assertTrue(all.toString().endsWith(unz));
        assertTrue(unz.endsWith("#0399\n"));
        assertTrue(unz.startsWith("21:41:"));
        assertTrue("compresse", zip.length() < n);
        st.clear();
        assertEquals(0, st.size());
    }

    @Test
    public void hpManaExpEtDegatsRecus() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        // trames reelles du 21/11
        gs.message(hexBytes("62 00 00 97 e1 e9 72"));                       // ton ID
        gs.message(hexBytes("4d 00 97 e1 c0 37 01 97 e1 e9 72 00 00 06"));   // 6 degats
        gs.message(hexBytes("4d 00 97 e1 ca 25 01 97 e1 e9 72 01 00 0d"));   // 13, critique
        gs.message(hexBytes("4d 00 97 e1 c2 5c 01 97 e1 e9 72 02 00 00"));   // rate : rien
        gs.message(hexBytes("a6 02 05 03"));                                 // cristaux : seulement en Details
        gs.message(hexBytes("5c 02 00 81 d6 bb 53"));
        gs.message(hexBytes("5c 02 00 81 d6 bb 5a"));
        gs.message(hexBytes("5c 00 00 87 25"));                              // potion (capture du 20/11)
        gs.message(hexBytes("5c 02 04 81 24"));
        java.util.List<String> l = gs.lines();
        String all = String.join("\n", l);
        assertEquals(all, 5, l.size());
        assertTrue(all, l.get(0).endsWith("?97e1c037 hits you for 6"));
        assertTrue(all, l.get(1).endsWith("?97e1ca25 hits you for 13 (critical)"));
        assertTrue(all, l.get(2).endsWith("+7 EXP (total 3,513,818)"));
        assertTrue(all, l.get(3).endsWith("HP: 933"));
        assertTrue(all, l.get(4).endsWith("MP: 164"));
        assertEquals("0.5", GameState.sixths(3));
        assertEquals("1", GameState.sixths(6));
        assertEquals("1/6", GameState.sixths(1));
        assertEquals("3", GameState.sixths(18));
    }

    @Test
    public void estimationDesCristaux() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        gs.setSkillCosts("7=2 11=3 12=2, 4=1.5");
        assertEquals("", gs.status());                    // rien vu : pas d'affichage
        // sequence reelle du 21/11 : competences 11 et 12, puis coups de zone
        gs.crystalsCast(11);
        gs.crystalsCast(12);
        assertEquals(0, gs.crystalsSixths());
        gs.message(hexBytes("a6 02 05 03"));
        gs.message(hexBytes("a6 02 05 06"));
        assertEquals(9, gs.crystalsSixths());              // 1,5 cristal
        gs.crystalsCast(7);                                // coute 2 : il en avait donc au moins 2
        assertEquals(0, gs.crystalsSixths());
        for (int i = 0; i < 10; i++) gs.message(hexBytes("a6 02 05 06"));
        assertEquals(GameState.CRYSTAL_MAX, gs.crystalsSixths());   // jamais plus de 5
        gs.message(hexBytes("a6 02 05 43"));               // -3 envoye par le serveur
        gs.crystalsCast(4);
        assertEquals(18, gs.crystalsSixths());
        gs.crystalsCast(3);                                // cout inconnu : 0
        gs.message(hexBytes("62 02 00 87 25 0e 4d 00 61 00 72 00 72 00 69 00 6f 00 72 00 05 01"));
        assertEquals("Crystals ~3 / 5   HP 933", gs.status());
        assertEquals("4.5", GameState.sixthsRounded(28));
        assertEquals("0", GameState.sixthsRounded(2));
    }

    @Test
    public void hpDesMobs() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        gs.message(hexBytes("62 00 00 97 e1 e9 72"));
        // liste reelle des entites en vue (21/11), avec trois Yeti Highguard et deux Enraged Wings
        String d6 = new String(resource("/raw_mobhp.txt"), java.nio.charset.StandardCharsets.UTF_8).trim();
        java.util.List<TwomDecoder.Entity> ents = TwomDecoder.parseEntities(hexBytes(d6), 2);
        int yetis = 0;
        for (TwomDecoder.Entity e : ents) {
            if (e.name.equals("Yeti Highguard")) { yetis++; assertEquals(1650, e.maxHp); assertEquals(1650, e.hp); }
            if (e.name.equals("Enraged Wings")) assertEquals(1520, e.maxHp);
        }
        assertEquals(3, yetis);
        gs.message(hexBytes(d6));
        // ton coup de zone : 109, 99 et 187 degats
        gs.message(hexBytes("4d 03 97 e1 e9 72 03 97 e1 c0 37 00 00 80 6d 97 e1 c1 42 00 00 80 63 97 e1 c2 5c 05 00 81 3b"));
        String sum = gs.mobsSummary();
        assertTrue(sum, sum.contains("HP 1541/1650"));
        assertTrue(sum, sum.contains("HP 1551/1650"));
        assertTrue(sum, sum.contains("HP 1463/1650"));
    }

    @Test
    public void buffsTeleportEquipement() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        // trames reelles du 21/11 (17h)
        gs.message(hexBytes("57 20 81 3f 00 00 00 00 00 12 4f 80 00 00 00 00 00 10 7a c0 14 01"));   // consommable
        gs.message(hexBytes("57 81 04 8c 38 00 00 00 00 00 00 ea 60 00 00 00 00 00 00 de a8 03 02")); // 3e kill
        gs.message(hexBytes("5c 01 02 81 02"));
        gs.message(hexBytes("8e 05"));
        gs.message(hexBytes("8e 19"));
        gs.message(hexBytes("8e 07"));
        // casque retire puis arme remise : vraies trames chiffrees
        for (String f : new String[]{"c1 35 46 40 2c f0 55 d5 35 15 05 42 fe 55 55 f5 f7 75 d7 96 b7 75 f4 d5 97 d5 55 55 55 55 55 55 13", "46 1c 85 86 b0 de 0c 2c 3c 3c 14 a2 af 0c 0c 6c 5d 3c 4d bd 7d 1c dc 4c 1c 5c 6d 7c 1c 0c 0c 0c 0c 0c 98"}) {
            byte[] eq = TwomDecoder.decodeClientFrame(hexBytes(f), "98a8cb2d");
            assertNotNull(f, eq);
            gs.clientMessage(eq);
        }
        java.util.List<String> l;
        String all;
        l = gs.lines();
        all = String.join("\n", l);
        assertTrue(all, l.get(0).endsWith("Buff #32 started (20 min)"));
        assertTrue(all, l.get(1).endsWith("Buff #132 started (1 min) x3"));
        assertTrue(all, l.get(2).endsWith("Move speed: 130"));
        assertTrue(all, l.get(3).endsWith("Teleporting to Wingfield Beach (map #5)"));
        assertTrue(all, l.get(4).endsWith("Teleporting to Forest of Grave (map #25)"));
        assertTrue(all, l.get(5).endsWith("Teleporting to map #7"));
        assertTrue(all, l.get(6).endsWith("You unequip item #40029"));
        assertTrue(all, l.get(7).endsWith("You equip item #30010"));
        assertEquals("6 s", GameState.duration(6000));
    }

    @Test
    public void messagesCourts4cToutesCles() {
        byte[] plain = hexBytes("4c 00 99 a3 d4 61 00 99 a8 80 05 01 01 0d 00");
        int ok = 0;
        for (int k = 0; k < 256; k++) {
            java.util.List<byte[]> r = TwomDecoder.decodeFrame(encrypt(plain, k), new java.util.HashMap<String, Object>());
            if (r.size() == 1 && TwomDecoder.hex(r.get(0), 0, 14).equals("4c0099a3d46100" + "99a8800501010d")) ok++;
        }
        assertEquals(256, ok);
    }

    @Test
    public void tableauDesNoms() {
        Names n = new Names();
        int[] changes = {0};
        n.onChange = () -> changes[0]++;
        // valeurs connues d'avance
        assertEquals("Wingfield Beach (map #5)", n.label(Names.Cat.MAP, 5));
        assertEquals("map #7", n.label(Names.Cat.MAP, 7));
        assertEquals(12, n.crystalSixths(7));
        // un nouveau numero vu en jeu apparait une seule fois
        n.see(Names.Cat.SKILL, 3, null);
        n.see(Names.Cat.SKILL, 3, null);
        assertEquals(1, changes[0]);
        assertEquals(2, n.get(Names.Cat.SKILL, "3").seen);
        // le joueur le nomme
        n.get(Names.Cat.SKILL, "3").name = "Power Strike";
        n.get(Names.Cat.SKILL, "3").crystals = 1.5;
        n.get(Names.Cat.STAT, "3/23").name = "Crit rate, x100";
        n.get(Names.Cat.ITEM, "130005").note = "tab\there, line\nbreak, \\";
        assertEquals("Power Strike (skill #3)", n.label(Names.Cat.SKILL, 3));
        assertEquals(9, n.crystalSixths(3));
        // ordre numerique, stats "groupe/numero" comprises
        java.util.List<Names.Entry> st = n.list(Names.Cat.STAT);
        assertEquals("0/0", st.get(0).id);
        assertEquals("3/24", st.get(st.size() - 1).id);
        java.util.List<Names.Entry> sk = n.list(Names.Cat.SKILL);
        assertEquals("3", sk.get(0).id);
        // enregistrement et rechargement
        Names m = new Names();
        m.load(n.serialize());
        assertEquals("Power Strike (skill #3)", m.label(Names.Cat.SKILL, 3));
        assertEquals(9, m.crystalSixths(3));
        assertEquals("tab\there, line\nbreak, \\", m.get(Names.Cat.ITEM, "130005").note);
        assertTrue(m.csv().contains("Stats,3/23,\"Crit rate, x100\","));
        assertTrue(m.csv().contains("Skills,3,Power Strike,1.5,0,"));
    }

    @Test
    public void nomsDansLeLog() {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        Names.Entry e;
        gs.names.see(Names.Cat.BUFF, 32, null);
        gs.names.get(Names.Cat.BUFF, "32").name = "Strength Potion";
        gs.names.get(Names.Cat.STAT, "3/23").name = "Crit rate";
        gs.message(hexBytes("57 20 81 3f 00 00 00 00 00 12 4f 80 00 00 00 00 00 10 7a c0 14 01"));
        gs.message(hexBytes("5c 03 17 ad 3c"));
        gs.message(hexBytes("5c 03 18 81 47"));        // stat sans nom : seulement avec Details
        java.util.List<String> l = gs.lines();
        String all = String.join("\n", l);
        assertEquals(all, 2, l.size());
        assertTrue(all, l.get(0).endsWith("Strength Potion (buff #32) started (20 min)"));
        assertTrue(all, l.get(1).endsWith("Crit rate: 5820"));
        assertEquals("199", gs.names.get(Names.Cat.STAT, "3/24").last);
        assertEquals("20 min", gs.names.get(Names.Cat.BUFF, "32").last);
        gs.names.get(Names.Cat.BUFF, "32").name = "";
        gs.names.get(Names.Cat.STAT, "3/23").name = "";
    }

    @Test
    public void inventaireEtAlerte() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        gs.invThreshold = 3;
        java.util.List<String> alerts = new java.util.ArrayList<>();
        GameState.AlertListener al = new GameState.AlertListener() {
            @Override public void onMatch(TwomDecoder.Entity e, boolean spawned) { }
            @Override public void onAlert(String title, String text, String speech) { alerts.add(title); }
        };
        gs.addAlertListener(al);
        try {
            // les 13 pages reelles envoyees a l'entree sur une map (21/11, 21h11)
            String all = new String(resource("/raw_inventory.txt"), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : all.split("\n")) if (!line.trim().isEmpty()) gs.message(hexBytes(line.trim()));
            int[] inv = gs.inventory();
            assertNotNull(inv);
            assertEquals(82, inv[0]);
            assertEquals(86, inv[1]);
            assertTrue(gs.status(), gs.status().contains("Bag 82/86"));
            assertTrue(alerts.isEmpty());
            gs.message(hexBytes("87 02 03 89 95 04 00 00 00 01 00"));      // un objet dans une case libre
            assertEquals(83, gs.inventory()[0]);
            assertEquals(java.util.Arrays.asList("Inventory almost full: 3 free slots (83/86)"), alerts);
            gs.message(hexBytes("87 01 05 00 00 00 00 00 00 00 00"));      // une case videe (vente)
            gs.message(hexBytes("87 02 03 89 95 04 00 00 00 01 00"));      // deja occupee : rien ne change
            assertEquals(82, gs.inventory()[0]);
            assertEquals(1, alerts.size());
        } finally {
            gs.removeAlertListener(al);
        }
    }

    @Test
    public void joueursEtAlerteEnnemi() throws Exception {
        GameState gs = GameState.INSTANCE;
        gs.reset();
        java.util.List<String> alerts = new java.util.ArrayList<>();
        GameState.AlertListener al = new GameState.AlertListener() {
            @Override public void onMatch(TwomDecoder.Entity e, boolean spawned) { }
            @Override public void onAlert(String title, String text, String speech) { alerts.add(title + " | " + text); }
        };
        gs.addAlertListener(al);
        try {
            String d6 = new String(resource("/raw_players.txt"), java.nio.charset.StandardCharsets.UTF_8).trim();
            TwomDecoder.Entity lekz = null;
            for (TwomDecoder.Entity e : TwomDecoder.parseEntities(hexBytes(d6), 2)) if (e.name.equals("Lekz")) lekz = e;
            assertNotNull(lekz);
            assertTrue(lekz.player);
            assertEquals(34, lekz.plvl);
            assertEquals(2, lekz.cls);
            assertEquals(3, lekz.faction);
            assertEquals(0, lekz.status);
            assertEquals("9b1a", lekz.guild);
            assertEquals(598, lekz.maxHp);

            gs.message(hexBytes("a9 02 9b 1a 02 01 10 47 00 61 00 6e 00 67 00 73 00 54 00 65 00 72 00 03"));
            gs.setPlayerAlert("3", "");
            gs.message(hexBytes(d6));
            assertTrue(alerts.toString(), alerts.stream().anyMatch(x -> x.startsWith("Enemy player: Lekz")));
            assertTrue(alerts.toString(), alerts.stream().anyMatch(x -> x.contains("Lekz lvl 34 class 2 [GangsTer], faction 3, status 0")));
            assertTrue(gs.mobsSummary(), gs.mobsSummary().contains("player(s) in view"));
            int n = alerts.size();
            gs.message(hexBytes(d6));                    // toujours en vue : pas de nouvelle alerte
            assertEquals(n, alerts.size());
            // seulement le statut 2 : Lekz (statut 0) ne declenche plus rien
            gs.reset();
            alerts.clear();
            gs.setPlayerAlert("3", "2");
            gs.message(hexBytes(d6));
            assertFalse(alerts.toString(), alerts.stream().anyMatch(x -> x.startsWith("Enemy player: Lekz")));
        } finally {
            gs.removeAlertListener(al);
            gs.setPlayerAlert("", "");
        }
    }

    // ---------------------------------------------------------------- relais
    static final byte[] APP = {10, 0, 0, 2}, SRV = {127, 0, 0, 1};
    final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();

    Packets.Ip next() throws Exception {
        byte[] p = q.poll(5, TimeUnit.SECONDS);
        assertNotNull("pas de paquet", p);
        assertTrue("checksum", Packets.checksumOk(p));
        return Packets.Ip.parse(p, p.length);
    }

    @Test
    public void relaisTcpEtUdp() throws Exception {
        byte[] stream = capture();
        ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        int port = ss.getLocalPort();
        byte[] got = new byte[5];
        Thread srv = new Thread(() -> {
            try (Socket s = ss.accept()) {
                new DataInputStream(s.getInputStream()).readFully(got);
                OutputStream o = s.getOutputStream();
                Random r = new Random(2);
                for (int i = 0; i < stream.length; ) {
                    int n = Math.min(stream.length - i, 1 + r.nextInt(3000));
                    o.write(stream, i, n);
                    o.flush();
                    i += n;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        srv.start();
        GameState gs = GameState.INSTANCE;
        gs.reset();
        Relay relay = new Relay(q::add, new Relay.Protector() {
            public boolean protect(Socket s) { return true; }
            public boolean protect(DatagramSocket s) { return true; }
        }, port, gs);
        try {
            int WIN = 6000;
            byte[] syn = Packets.tcp(APP, 40000, SRV, port, 1000, 0, Packets.SYN, null, 0, 0, 1400, WIN);
            relay.handle(syn, syn.length);
            Packets.Ip sa = next();
            assertEquals(Packets.SYN | Packets.ACK, sa.flags & (Packets.SYN | Packets.ACK));
            assertEquals(1001, sa.ack);
            long srvSeq = (sa.seq + 1) & Packets.MASK, appSeq = 1006;
            byte[] hello = "hello".getBytes();
            byte[] d = Packets.tcp(APP, 40000, SRV, port, 1001, srvSeq, Packets.ACK | Packets.PSH, hello, 0, 5, 0, WIN);
            relay.handle(d, d.length);
            assertEquals(1006, next().ack);
            ByteArrayOutputStream rx = new ByteArrayOutputStream();
            boolean dropped = false;
            while (true) {
                Packets.Ip p = next();
                if (p.dataLen > 0) {
                    if (!dropped && rx.size() > 20000) { dropped = true; continue; }   // perte simulee
                    if (p.seq == srvSeq) {
                        rx.write(p.raw, p.dataOff, p.dataLen);
                        srvSeq = (srvSeq + p.dataLen) & Packets.MASK;
                    }
                    byte[] ack = Packets.tcp(APP, 40000, SRV, port, appSeq, srvSeq, Packets.ACK, null, 0, 0, 0, WIN);
                    relay.handle(ack, ack.length);
                }
                if ((p.flags & Packets.FIN) != 0 && p.seq == srvSeq) {
                    srvSeq = (srvSeq + 1) & Packets.MASK;
                    byte[] fin = Packets.tcp(APP, 40000, SRV, port, appSeq, srvSeq, Packets.FIN | Packets.ACK, null, 0, 0, 0, WIN);
                    relay.handle(fin, fin.length);
                    assertEquals(appSeq + 1, next().ack);
                    break;
                }
            }
            srv.join(3000);
            assertEquals("hello", new String(got));
            assertTrue("flux identique", Arrays.equals(stream, rx.toByteArray()));
            Thread.sleep(200);
            assertEquals(6, gs.deaths);

            DatagramSocket echo = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            new Thread(() -> {
                try {
                    byte[] b = new byte[2000];
                    DatagramPacket dp = new DatagramPacket(b, b.length);
                    echo.receive(dp);
                    echo.send(new DatagramPacket(b, dp.getLength(), dp.getSocketAddress()));
                } catch (Exception ignored) { }
            }).start();
            byte[] u = Packets.udp(APP, 5353, SRV, echo.getLocalPort(), "dns?".getBytes(), 0, 4);
            relay.handle(u, u.length);
            Packets.Ip ur = next();
            assertEquals(17, ur.proto);
            assertEquals(5353, ur.dport);
            assertEquals("dns?", new String(ur.payload()));
        } finally {
            relay.stop();
        }
    }
}
