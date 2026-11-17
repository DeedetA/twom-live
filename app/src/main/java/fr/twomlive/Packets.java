package fr.twomlive;

/** Lecture / fabrication de paquets IPv4 + TCP/UDP. Aucune dependance Android. */
public final class Packets {
    private Packets() {}

    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;
    public static final long MASK = 0xffffffffL;
    private static int ipId = 1;

    public static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }
    public static long u32(byte[] b, int i) {
        return (((long) (b[i] & 0xff)) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }
    static void w16(byte[] b, int i, int v) { b[i] = (byte) (v >>> 8); b[i + 1] = (byte) v; }
    static void w32(byte[] b, int i, long v) {
        b[i] = (byte) (v >>> 24); b[i + 1] = (byte) (v >>> 16); b[i + 2] = (byte) (v >>> 8); b[i + 3] = (byte) v;
    }

    /** sequence a avant b (arithmetique modulo 2^32) */
    public static boolean before(long a, long b) { return ((a - b) & MASK) > 0x7fffffffL; }

    static long sum(byte[] b, int off, int len, long acc) {
        int i = off;
        for (; i + 1 < off + len; i += 2) acc += ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
        if (i < off + len) acc += (b[i] & 0xff) << 8;
        return acc;
    }

    static int fold(long acc) {
        while ((acc >>> 16) != 0) acc = (acc & 0xffff) + (acc >>> 16);
        return (int) (~acc & 0xffff);
    }

    /** paquet IPv4 recu du tun */
    public static final class Ip {
        public byte[] raw;
        public int ihl, total, proto;
        public byte[] src = new byte[4], dst = new byte[4];
        // TCP / UDP
        public int sport, dport, flags, window, dataOff, dataLen;
        public long seq, ack;

        public static Ip parse(byte[] b, int len) {
            if (len < 20 || ((b[0] & 0xff) >> 4) != 4) return null;
            Ip p = new Ip();
            p.raw = b;
            p.ihl = (b[0] & 0x0f) * 4;
            p.total = Math.min(u16(b, 2), len);
            p.proto = b[9] & 0xff;
            System.arraycopy(b, 12, p.src, 0, 4);
            System.arraycopy(b, 16, p.dst, 0, 4);
            int t = p.ihl;
            if (p.proto == 6) {
                if (p.total < t + 20) return null;
                p.sport = u16(b, t);
                p.dport = u16(b, t + 2);
                p.seq = u32(b, t + 4);
                p.ack = u32(b, t + 8);
                int off = ((b[t + 12] & 0xff) >> 4) * 4;
                p.flags = b[t + 13] & 0xff;
                p.window = u16(b, t + 14);
                p.dataOff = t + off;
                p.dataLen = Math.max(0, p.total - p.dataOff);
            } else if (p.proto == 17) {
                if (p.total < t + 8) return null;
                p.sport = u16(b, t);
                p.dport = u16(b, t + 2);
                p.dataOff = t + 8;
                p.dataLen = Math.max(0, p.total - p.dataOff);
            } else {
                return null;
            }
            return p;
        }

        public byte[] payload() {
            byte[] o = new byte[dataLen];
            System.arraycopy(raw, dataOff, o, 0, dataLen);
            return o;
        }
    }

    private static synchronized int nextId() { ipId = (ipId + 1) & 0xffff; return ipId; }

    private static void ipHeader(byte[] p, int total, int proto, byte[] src, byte[] dst) {
        p[0] = 0x45;
        w16(p, 2, total);
        w16(p, 4, nextId());
        w16(p, 6, 0x4000);
        p[8] = 64;
        p[9] = (byte) proto;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        w16(p, 10, fold(sum(p, 0, 20, 0)));
    }

    private static long pseudo(byte[] src, byte[] dst, int proto, int len) {
        long acc = sum(src, 0, 4, 0);
        acc = sum(dst, 0, 4, acc);
        return acc + proto + len;
    }

    /** segment TCP du "serveur" (src) vers l'appli (dst) */
    public static byte[] tcp(byte[] src, int sport, byte[] dst, int dport, long seq, long ack, int flags,
                             byte[] data, int off, int len, int mss) {
        return tcp(src, sport, dst, dport, seq, ack, flags, data, off, len, mss, 65535);
    }

    public static byte[] tcp(byte[] src, int sport, byte[] dst, int dport, long seq, long ack, int flags,
                             byte[] data, int off, int len, int mss, int window) {
        int opt = mss > 0 ? 4 : 0;
        int tl = 20 + opt + len;
        byte[] p = new byte[20 + tl];
        int t = 20;
        w16(p, t, sport);
        w16(p, t + 2, dport);
        w32(p, t + 4, seq & MASK);
        w32(p, t + 8, ack & MASK);
        p[t + 12] = (byte) (((20 + opt) / 4) << 4);
        p[t + 13] = (byte) flags;
        w16(p, t + 14, window);
        if (mss > 0) { p[t + 20] = 2; p[t + 21] = 4; w16(p, t + 22, mss); }
        if (len > 0) System.arraycopy(data, off, p, t + 20 + opt, len);
        w16(p, t + 16, fold(sum(p, t, tl, pseudo(src, dst, 6, tl))));
        ipHeader(p, 20 + tl, 6, src, dst);
        return p;
    }

    public static byte[] udp(byte[] src, int sport, byte[] dst, int dport, byte[] data, int off, int len) {
        int ul = 8 + len;
        byte[] p = new byte[20 + ul];
        w16(p, 20, sport);
        w16(p, 22, dport);
        w16(p, 24, ul);
        System.arraycopy(data, off, p, 28, len);
        int c = fold(sum(p, 20, ul, pseudo(src, dst, 17, ul)));
        w16(p, 26, c == 0 ? 0xffff : c);
        ipHeader(p, 20 + ul, 17, src, dst);
        return p;
    }

    public static boolean checksumOk(byte[] p) {
        Ip ip = Ip.parse(p, p.length);
        if (ip == null) return false;
        if (fold(sum(p, 0, ip.ihl, 0)) != 0) return false;
        int l = ip.total - ip.ihl;
        return fold(sum(p, ip.ihl, l, pseudo(ip.src, ip.dst, ip.proto, l))) == 0;
    }
}
