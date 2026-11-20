package fr.twomlive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Partage en lecture seule du fichier "Raw" (et de lui seul) avec l'appli choisie par l'utilisateur.
 * Non exporte : l'acces n'est donne que via FLAG_GRANT_READ_URI_PERMISSION.
 */
public class RawFileProvider extends ContentProvider {
    public static final String AUTHORITY = "fr.twomlive.files";
    public static final Uri RAW_URI = Uri.parse("content://" + AUTHORITY + "/" + RawRecorder.FILE);

    private File target() {
        return new File(getContext().getCacheDir(), RawRecorder.FILE);
    }

    private void check(Uri uri) throws FileNotFoundException {
        if (!RawRecorder.FILE.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check(uri);
        return ParcelFileDescriptor.open(target(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!RawRecorder.FILE.equals(uri.getLastPathSegment())) return null;
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{RawRecorder.FILE, target().length()});
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
