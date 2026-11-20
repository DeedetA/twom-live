package fr.twomlive;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Enregistre les lignes du mode "Raw" dans un fichier de l'appli (cache), pour l'export. */
public final class RawRecorder implements GameState.RawSink {
    public static final String FILE = "twom_raw.txt";
    private static final long MAX_BYTES = 20L * 1024 * 1024;   // au-dela, on repart d'un fichier vide

    private static RawRecorder instance;
    private final File file;
    private BufferedWriter out;
    private long size;
    private int pending;

    public static synchronized RawRecorder get(Context c) {
        if (instance == null) instance = new RawRecorder(new File(c.getApplicationContext().getCacheDir(), FILE));
        return instance;
    }

    private RawRecorder(File f) {
        file = f;
        size = f.length();
    }

    public File file() { return file; }

    private void open(boolean append) throws IOException {
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, append), StandardCharsets.UTF_8));
        if (!append) size = 0;
    }

    @Override
    public synchronized void write(String line) {
        try {
            if (size > MAX_BYTES) { close(); open(false); }
            if (out == null) open(true);
            out.write(line);
            out.write('\n');
            size += line.length() + 1;
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
        try { open(false); } catch (IOException ignored) { }
    }

    private void close() {
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
    }
}
