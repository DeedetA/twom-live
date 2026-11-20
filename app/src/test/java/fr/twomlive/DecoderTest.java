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
        assertTrue(l.toString(), l.get(2).endsWith("+78 EXP"));
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
