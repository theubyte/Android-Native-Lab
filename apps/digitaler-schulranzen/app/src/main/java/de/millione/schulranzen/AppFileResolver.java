package de.millione.schulranzen;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Resolves app-owned book files independently from Android document-provider permissions. */
public final class AppFileResolver {
    private static final String PREFIX = "appfile:";
    private AppFileResolver() {}

    public static boolean isAppFile(String value) { return value != null && value.startsWith(PREFIX); }

    public static File resolve(Context c, String value) {
        if (!isAppFile(value)) return null;
        String rel = value.substring(PREFIX.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        File root = c.getFilesDir();
        try {
            File f = new File(root, rel);
            String rp = root.getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(rp) ? f : null;
        } catch (Exception e) { return null; }
    }

    public static String bookToken(String id) { return PREFIX + "books/" + safe(id) + ".pdf"; }

    public static File bookFile(Context c, String id) {
        File dir = new File(c.getFilesDir(), "books");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, safe(id) + ".pdf");
    }

    public static String copyBook(Context c, Uri source, String id) throws Exception {
        if (source == null) throw new IllegalArgumentException("Keine PDF-Quelle");
        File dest = bookFile(c, id);
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (InputStream in = c.getContentResolver().openInputStream(source); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalStateException("PDF konnte nicht geöffnet werden");
            copy(in, out);
        }
        if (dest.exists() && !dest.delete()) throw new IllegalStateException("Alte PDF konnte nicht ersetzt werden");
        if (!tmp.renameTo(dest)) {
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) { copy(in, out); }
            tmp.delete();
        }
        return bookToken(id);
    }

    public static InputStream openInputStream(Context c, String value) throws Exception {
        File f = resolve(c, value);
        if (f != null) return new FileInputStream(f);
        return c.getContentResolver().openInputStream(Uri.parse(value));
    }

    public static ParcelFileDescriptor openPfd(Context c, String value) throws Exception {
        File f = resolve(c, value);
        if (f != null) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        return c.getContentResolver().openFileDescriptor(Uri.parse(value), "r");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    private static String safe(String id){return (id==null?"book":id).replaceAll("[^a-zA-Z0-9._-]","_");}
}
