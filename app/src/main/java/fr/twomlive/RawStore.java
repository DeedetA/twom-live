package fr.twomlive;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Enregistrement "Raw" pour de longues seances : les lignes sont ecrites dans des morceaux
 * (raw_000001.txt, raw_000002.txt...) de taille limitee. Quand le total depasse la limite, on supprime
 * les plus anciens morceaux, jamais tout d'un coup. Au partage, tous les morceaux sont recolles dans
 * un seul twom_raw.txt, compresse dans un .zip.
 */
public final class RawStore {
    public static final String ZIP_ENTRY = "twom_raw.txt";

    private final File dir;
    private final long segmentBytes, totalBytes, minFreeBytes;
    private BufferedWriter out;
    private File current;
    private long currentSize;
    private int pending;

    public RawStore(File dir, long segmentBytes, long totalBytes, long minFreeBytes) {
        this.dir = dir;
        this.segmentBytes = segmentBytes;
        this.totalBytes = totalBytes;
        this.minFreeBytes = minFreeBytes;
        dir.mkdirs();
    }

    /** morceaux, du plus ancien au plus recent */
    public synchronized File[] segments() {
        File[] f = dir.listFiles((d, n) -> n.startsWith("raw_") && n.endsWith(".txt"));
        if (f == null) return new File[0];
        Arrays.sort(f, (a, b) -> a.getName().compareTo(b.getName()));
        return f;
    }

    public synchronized long size() {
        long s = 0;
        for (File f : segments()) s += f.length();
        return s;
    }

    private int lastIndex() {
        File[] f = segments();
        if (f.length == 0) return 0;
        String n = f[f.length - 1].getName();
        try { return Integer.parseInt(n.substring(4, n.length() - 4)); } catch (NumberFormatException e) { return 0; }
    }

    private void startSegment() throws IOException {
        close();
        current = new File(dir, String.format(java.util.Locale.US, "raw_%06d.txt", lastIndex() + 1));
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(current, false), StandardCharsets.UTF_8));
        currentSize = 0;
        // place totale limitee : on oublie le debut de la seance, pas la fin
        File[] f = segments();
        long total = 0;
        for (File s : f) total += s.length();
        for (int i = 0; i < f.length - 1 && total > totalBytes; i++) {
            total -= f[i].length();
            f[i].delete();
        }
    }

    public synchronized void write(String line) {
        try {
            if (out == null || currentSize >= segmentBytes) {
                if (dir.getUsableSpace() > 0 && dir.getUsableSpace() < minFreeBytes) return;   // telephone presque plein
                startSegment();
            }
            out.write(line);
            out.write('\n');
            currentSize += line.length() + 1;
            if (++pending >= 50) { out.flush(); pending = 0; }
        } catch (IOException ignored) {
            // stockage plein ou indisponible : on n'enregistre pas
        }
    }

    public synchronized void flush() {
        try { if (out != null) out.flush(); } catch (IOException ignored) { }
        pending = 0;
    }

    public synchronized void clear() {
        close();
        for (File f : segments()) f.delete();
    }

    private void close() {
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
    }

    /** recolle tous les morceaux dans un seul twom_raw.txt compresse dans zip ; renvoie la taille non compressee */
    public long zipTo(File zip) throws IOException {
        File[] f;
        synchronized (this) {
            flush();
            f = segments();
        }
        long n = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(ZIP_ENTRY));
            for (File s : f) {
                // le morceau en cours peut grandir pendant la copie : on s'arrete a sa taille actuelle
                long len = s.length();
                try (InputStream in = new FileInputStream(s)) {
                    long left = len;
                    int r;
                    while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        z.write(buf, 0, r);
                        left -= r;
                        n += r;
                    }
                } catch (IOException ignored) {
                    // morceau supprime entre-temps : on passe au suivant
                }
            }
            z.closeEntry();
        }
        return n;
    }
}
