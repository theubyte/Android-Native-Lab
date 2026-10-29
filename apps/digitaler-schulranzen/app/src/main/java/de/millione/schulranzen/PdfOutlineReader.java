package de.millione.schulranzen;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.InputStream;
import java.util.ArrayList;

/** Reads embedded PDF bookmarks/outline without rendering pages. */
public final class PdfOutlineReader {
    public static final class Entry {
        public final String title;
        public final int pageIndex;
        public final int level;
        Entry(String title, int pageIndex, int level) { this.title = title; this.pageIndex = pageIndex; this.level = level; }
    }
    public interface Callback { void done(ArrayList<Entry> entries, String error); }

    private PdfOutlineReader() {}

    public static void load(Context context, String uriString, Callback callback) {
        new Thread(() -> {
            ArrayList<Entry> result = new ArrayList<>();
            String error = null;
            PDDocument doc = null;
            InputStream in = null;
            try {
                PDFBoxResourceLoader.init(context.getApplicationContext());
                in = AppFileResolver.openInputStream(context, uriString);
                if (in == null) throw new IllegalStateException("PDF nicht lesbar");
                doc = PDDocument.load(in);
                PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
                if (outline != null) walk(doc, outline.getFirstChild(), 0, result, 0);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName();
            } finally {
                try { if (doc != null) doc.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
            }
            final String err = error;
            final ArrayList<Entry> out = result;
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(() -> callback.done(out, err));
            } else callback.done(out, err);
        }, "pdf-outline").start();
    }

    private static int walk(PDDocument doc, PDOutlineItem item, int level, ArrayList<Entry> out, int seen) {
        PDOutlineItem cur = item;
        while (cur != null && seen < 800) {
            try {
                PDPage p = cur.findDestinationPage(doc);
                int index = p == null ? -1 : doc.getPages().indexOf(p);
                String title = cur.getTitle() == null ? "" : cur.getTitle().trim();
                if (!title.isEmpty() && index >= 0) { out.add(new Entry(title, index, Math.min(5, level))); seen++; }
                if (cur.getFirstChild() != null) seen = walk(doc, cur.getFirstChild(), level + 1, out, seen);
            } catch (Throwable ignored) { }
            cur = cur.getNextSibling();
        }
        return seen;
    }
}
