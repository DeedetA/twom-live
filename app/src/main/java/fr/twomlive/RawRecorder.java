package fr.twomlive;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/** Enregistre les lignes du mode "Raw" pour l'export (voir RawStore). */
public final class RawRecorder implements GameState.RawSink {
    public static final String ZIP = "twom_raw.zip";
    private static final long MB = 1024L * 1024;

    private static RawRecorder instance;
    private final Context ctx;
    // morceaux de 16 Mo, 400 Mo au total (plus de 30 h sur une map tres chargee),
    // et on arrete d'ecrire s'il reste moins de 200 Mo libres sur le telephone
    private final RawStore store;

    public static synchronized RawRecorder get(Context c) {
        if (instance == null) instance = new RawRecorder(c.getApplicationContext());
        return instance;
    }

    private RawRecorder(Context c) {
        ctx = c;
        store = new RawStore(new File(c.getFilesDir(), "raw"), 16 * MB, 400 * MB, 200 * MB);
        new File(c.getCacheDir(), "twom_raw.txt").delete();        // ancien fichier unique (versions <= 1.18)
    }

    @Override public void write(String line) { store.write(line); }
    public void flush() { store.flush(); }
    public void clear() { store.clear(); zipFile().delete(); }
    public long size() { return store.size(); }

    public File zipFile() { return new File(ctx.getCacheDir(), ZIP); }

    /** cree le .zip a partager (a appeler hors du fil de l'interface) ; renvoie la taille non compressee */
    public long makeZip() throws IOException { return store.zipTo(zipFile()); }
}
