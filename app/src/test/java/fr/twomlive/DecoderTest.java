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
