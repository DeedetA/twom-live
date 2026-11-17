package fr.twomlive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Relais transparent : les paquets de TWOM arrivent du tun, on termine les connexions TCP
 * ici et on les rouvre vers les vrais serveurs (sockets "protegees" hors VPN). Les donnees
 * passent sans modification ; seul le flux serveur de jeu -> client est copie vers le decodeur.
 * Aucune dependance Android : le tun et la protection des sockets sont fournis par l'appelant.
 */
public final class Relay {

    public interface Tun { void write(byte[] packet); }

    public interface Protector {
        boolean protect(Socket s);
        boolean protect(DatagramSocket s);
    }

    static final int MSS = 1360;

    final Tun tun;
    final Protector prot;
    final int gamePort;
    final GameState gs;
    final ConcurrentHashMap<String, TcpConn> tcp = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, UdpFlow> udp = new ConcurrentHashMap<>();
    final ExecutorService pool = Executors.newCachedThreadPool();
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    volatile boolean running = true;

    public Relay(Tun tun, Protector prot, int gamePort, GameState gs) {
        this.tun = tun;
        this.prot = prot;
        this.gamePort = gamePort;
        this.gs = gs;
        timer.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    static String ip(byte[] a) {
        return (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + "." + (a[3] & 0xff);
    }

    /** un paquet lu sur le tun */
    public void handle(byte[] buf, int len) {
        if (!running) return;
        Packets.Ip p = Packets.Ip.parse(buf, len);
        if (p == null) return;
        if (p.proto == 6) handleTcp(p);
        else if (p.proto == 17) handleUdp(p);
    }

    private void handleTcp(Packets.Ip p) {
        String key = ip(p.src) + ":" + p.sport + ">" + ip(p.dst) + ":" + p.dport;
        TcpConn c = tcp.get(key);
        if (c == null) {
            if ((p.flags & Packets.SYN) != 0 && (p.flags & Packets.ACK) == 0) {
                c = new TcpConn(this, key, p);
                tcp.put(key, c);
                TcpConn cc = c;
                pool.execute(cc::run);
            } else if ((p.flags & Packets.RST) == 0) {
                // connexion inconnue : on la refuse proprement
                long seq = (p.flags & Packets.ACK) != 0 ? p.ack : 0;
                long ack = (p.seq + p.dataLen + ((p.flags & Packets.FIN) != 0 ? 1 : 0)) & Packets.MASK;
                tun.write(Packets.tcp(p.dst, p.dport, p.src, p.sport, seq, ack, Packets.RST | Packets.ACK, null, 0, 0, 0));
            }
            return;
        }
        c.onPacket(p);
    }

    private void handleUdp(Packets.Ip p) {
        String key = ip(p.src) + ":" + p.sport + ">" + ip(p.dst) + ":" + p.dport;
        UdpFlow f = udp.get(key);
        if (f == null) {
            try {
                f = new UdpFlow(this, key, p);
            } catch (IOException e) {
                return;
            }
            udp.put(key, f);
            UdpFlow ff = f;
            pool.execute(ff::loop);
        }
        f.send(p.payload());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (TcpConn c : tcp.values()) {
            try { c.tick(now); } catch (RuntimeException ignored) { }
        }
    }

    public void stop() {
        running = false;
        for (TcpConn c : tcp.values()) c.close(true);
        for (UdpFlow f : udp.values()) f.close();
        timer.shutdownNow();
        pool.shutdownNow();
    }

    // ===================================================================== TCP
    static final class Seg {
        final long seq;
        final byte[] data;
        final int flags;
        long sent;
        int tries;

        Seg(long seq, byte[] data, int flags) { this.seq = seq; this.data = data; this.flags = flags; }

        long end() {
            int extra = ((flags & (Packets.SYN | Packets.FIN)) != 0) ? 1 : 0;
            return (seq + data.length + extra) & Packets.MASK;
        }
    }

    static final class TcpConn {
        final Relay r;
        final String key;
        final byte[] appIp, srvIp;
        final int appPort, srvPort;
        long rcvNxt, sndNxt, sndUna;
        int appWnd;
        // On lit et on ecrit directement sur le SocketChannel : ses verrous de lecture et d'ecriture
        // sont separes. (Sur Android, les flux InputStream/OutputStream d'un SocketChannel partagent
        // un meme verrou : une lecture en attente bloquait l'envoi des donnees de TWOM.)
        volatile SocketChannel ch;
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        final ArrayDeque<Seg> unacked = new ArrayDeque<>();
        boolean appFin, srvFin, closed;
        int dupAcks;
        long lastActivity = System.currentTimeMillis();
        final GameState.Stream tap;

        TcpConn(Relay r, String key, Packets.Ip syn) {
            this.r = r;
            this.key = key;
            appIp = syn.src.clone();
            srvIp = syn.dst.clone();
            appPort = syn.sport;
            srvPort = syn.dport;
            rcvNxt = (syn.seq + 1) & Packets.MASK;
            sndNxt = ThreadLocalRandom.current().nextLong(1, 0x7fffffffL);
            sndUna = sndNxt;
            appWnd = syn.window;
            // toute connexion hors ports web/DNS est examinee ; le decodeur garde celle du jeu
            boolean web = srvPort == 80 || srvPort == 443 || srvPort == 853 || srvPort == 53 || srvPort == 5228;
            tap = (!web && r.gs != null) ? new GameState.Stream(r.gs, ip(srvIp) + ":" + srvPort) : null;
        }

        void run() {
            try {
                relay();
            } catch (RuntimeException e) {
                if (r.gs != null) r.gs.debug("Erreur relais " + ip(srvIp) + ":" + srvPort + " : " + e);
                close(true);
            }
        }

        private void relay() {
            String dest = ip(srvIp) + ":" + srvPort;
            try {
                SocketChannel c = SocketChannel.open();
                try { r.prot.protect(c.socket()); } catch (RuntimeException ignored) { }
                c.socket().setTcpNoDelay(true);
                c.socket().connect(new InetSocketAddress(InetAddress.getByAddress(srvIp), srvPort), 15000);
                ch = c;
            } catch (IOException e) {
                if (r.gs != null) r.gs.debug("Connexion impossible vers " + dest + " : " + e.getMessage());
                close(true);
                return;
            }
            if (r.gs != null) r.gs.debug("Connexion relayée vers " + dest);
            synchronized (this) {
                if (closed) return;
                Seg sa = new Seg(sndNxt, new byte[0], Packets.SYN | Packets.ACK);
                unacked.add(sa);
                sndNxt = sa.end();
                send(sa);
            }
            byte[] buf = new byte[MSS];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            try {
                while (true) {
                    bb.clear();
                    int n = ch.read(bb);
                    if (n < 0) break;
                    if (n == 0) continue;
                    if (tap != null) {
                        try { tap.feed(buf, 0, n); } catch (RuntimeException ignored) { }
                    }
                    if (!sendData(buf, n)) return;
                }
            } catch (IOException e) {
                // le serveur a coupe ; on termine proprement ci-dessous
            }
            synchronized (this) {
                if (closed) return;
                Seg fin = new Seg(sndNxt, new byte[0], Packets.FIN | Packets.ACK);
                unacked.add(fin);
                sndNxt = fin.end();
                srvFin = true;
                send(fin);
                maybeClose();
            }
        }

        private synchronized boolean sendData(byte[] buf, int n) {
            while (!closed && inflight() + n > Math.max(appWnd, MSS)) {
                try { wait(200); } catch (InterruptedException e) { return false; }
            }
            if (closed) return false;
            byte[] d = new byte[n];
            System.arraycopy(buf, 0, d, 0, n);
            Seg s = new Seg(sndNxt, d, Packets.ACK | Packets.PSH);
            unacked.add(s);
            sndNxt = s.end();
            send(s);
            return true;
        }

        private long inflight() { return (sndNxt - sndUna) & Packets.MASK; }

        private void send(Seg s) {
            s.sent = System.currentTimeMillis();
            s.tries++;
            int mss = (s.flags & Packets.SYN) != 0 ? MSS : 0;
            r.tun.write(Packets.tcp(srvIp, srvPort, appIp, appPort, s.seq, rcvNxt, s.flags, s.data, 0, s.data.length, mss));
        }

        private void ackNow() {
            r.tun.write(Packets.tcp(srvIp, srvPort, appIp, appPort, sndNxt, rcvNxt, Packets.ACK, null, 0, 0, 0));
        }

        synchronized void onPacket(Packets.Ip p) {
            if (closed) return;
            lastActivity = System.currentTimeMillis();
            if ((p.flags & Packets.RST) != 0) { close(false); return; }
            if ((p.flags & Packets.SYN) != 0) {
                // SYN repete : on renverra le SYN-ACK des que la connexion sortante est prete
                if (!unacked.isEmpty() && (unacked.peekFirst().flags & Packets.SYN) != 0) send(unacked.peekFirst());
                return;
            }
            if ((p.flags & Packets.ACK) != 0) {
                if (Packets.before(sndUna, p.ack) && !Packets.before(sndNxt, p.ack)) {
                    sndUna = p.ack;
                    dupAcks = 0;
                    while (!unacked.isEmpty() && !Packets.before(sndUna, unacked.peekFirst().end())) unacked.pollFirst();
                } else if (p.ack == sndUna && p.dataLen == 0 && !unacked.isEmpty() && ++dupAcks == 3) {
                    send(unacked.peekFirst());          // retransmission rapide
                }
                appWnd = p.window;
                notifyAll();
            }
            if (p.dataLen > 0) {
                if (p.seq == rcvNxt && ch != null) {
                    final byte[] d = p.payload();
                    rcvNxt = (rcvNxt + d.length) & Packets.MASK;
                    if (tap != null) {
                        try { tap.feedClient(d, 0, d.length); } catch (RuntimeException ignored) { }
                    }
                    final SocketChannel c = ch;
                    writer.execute(() -> {
                        try {
                            ByteBuffer w = ByteBuffer.wrap(d);
                            while (w.hasRemaining()) c.write(w);
                        } catch (IOException e) {
                            close(true);
                        }
                    });
                }
                ackNow();   // ACK (ou ACK duplique si hors sequence : l'appli renverra)
            }
            if ((p.flags & Packets.FIN) != 0 && ((p.seq + p.dataLen) & Packets.MASK) == rcvNxt) {
                rcvNxt = (rcvNxt + 1) & Packets.MASK;
                appFin = true;
                writer.execute(() -> {
                    try { SocketChannel c = ch; if (c != null) c.socket().shutdownOutput(); } catch (IOException ignored) { }
                });
                ackNow();
            }
            maybeClose();
        }

        private void maybeClose() {
            if (appFin && srvFin && unacked.isEmpty()) close(false);
        }

        synchronized void tick(long now) {
            if (closed) return;
            Seg s = unacked.peekFirst();
            if (s != null && now - s.sent > 1000L * Math.min(s.tries, 4)) {
                if (s.tries > 8) { close(true); return; }
                for (Seg x : unacked) send(x);          // on renvoie tout ce qui n'est pas acquitte
            }
            if (now - lastActivity > 10 * 60_000L && (appFin || srvFin)) close(true);
        }

        void close(boolean rst) {
            synchronized (this) {
                if (closed) return;
                closed = true;
                notifyAll();
                if (rst) {
                    r.tun.write(Packets.tcp(srvIp, srvPort, appIp, appPort, sndNxt, rcvNxt,
                            Packets.RST | Packets.ACK, null, 0, 0, 0));
                }
            }
            r.tcp.remove(key, this);
            try { SocketChannel c = ch; if (c != null) c.close(); } catch (IOException ignored) { }
            writer.shutdown();
            if (tap != null) tap.closed();
        }
    }

    // ===================================================================== UDP
    static final class UdpFlow {
        final Relay r;
        final String key;
        final byte[] appIp, dstIp;
        final int appPort, dstPort;
        final DatagramSocket s;
        final InetAddress dst;
        volatile long lastUsed = System.currentTimeMillis();
        volatile boolean closed;

        UdpFlow(Relay r, String key, Packets.Ip p) throws IOException {
            this.r = r;
            this.key = key;
            appIp = p.src.clone();
            dstIp = p.dst.clone();
            appPort = p.sport;
            dstPort = p.dport;
            dst = InetAddress.getByAddress(dstIp);
            s = new DatagramSocket();
            try { r.prot.protect(s); } catch (RuntimeException ignored) { }
            s.setSoTimeout(1000);
        }

        void send(byte[] data) {
            lastUsed = System.currentTimeMillis();
            try { s.send(new DatagramPacket(data, data.length, dst, dstPort)); } catch (IOException ignored) { }
        }

        void loop() {
            byte[] buf = new byte[65535];
            while (!closed && r.running) {
                try {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    s.receive(dp);
                    lastUsed = System.currentTimeMillis();
                    r.tun.write(Packets.udp(dstIp, dstPort, appIp, appPort, buf, 0, dp.getLength()));
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() - lastUsed > 60_000) break;
                } catch (IOException e) {
                    break;
                }
            }
            close();
        }

        void close() {
            closed = true;
            s.close();
            r.udp.remove(key, this);
        }
    }
}
