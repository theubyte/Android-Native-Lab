package de.millione.schulranzen;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline PDF book analysis.
 *
 * Priority:
 * 1) Embedded PDF outline/bookmarks.
 * 2) Printed table of contents detected from the text layer.
 * 3) Chapter/section headings detected directly on pages.
 *
 * The result is cached per book and all work happens off the UI thread. PDFBox uses a mixed
 * memory/temp-file strategy so large textbooks do not have to be copied completely into RAM.
 */
public final class PdfBookAnalyzer {
    public static final class Entry {
        public final String title;
        public final int pageIndex;
        public final int level;
        public final String source;
        Entry(String title, int pageIndex, int level, String source) {
            this.title = title; this.pageIndex = pageIndex; this.level = level; this.source = source;
        }
    }

    public static final class Result {
        public final ArrayList<Entry> entries = new ArrayList<>();
        public int pageCount;
        public boolean searchableText;
        public String documentTitle = "";
        public String source = "";
        public String summary = "";
        public String error = null;
        public long analyzedAt;
    }

    public interface Callback { void done(Result result); }

    private static final String PREF = "pdf_book_analysis_v11";
    private static final Object LOCK = new Object();
    private static final HashSet<String> RUNNING = new HashSet<>();
    private static final HashMap<String, ArrayList<Callback>> WAITERS = new HashMap<>();
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){0,4})[.)]?\\s+(.{3,120})$");
    private static final Pattern ENDING_PAGE = Pattern.compile("^(.{3,160}?)(?:\\.{2,}|\\s{1,})(\\d{1,4})$");
    private static final Pattern PLAIN_PAGE = Pattern.compile("^(?:seite\\s*)?(\\d{1,4})$", Pattern.CASE_INSENSITIVE);

    private PdfBookAnalyzer() {}

    public static Result cached(Context c, String bookId, String uri) {
        try {
            String raw = c.getSharedPreferences(PREF, 0).getString(key(bookId, uri), null);
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            if (!safe(uri).equals(o.optString("uri"))) return null;
            Result r = new Result();
            r.pageCount = o.optInt("pageCount", 0);
            r.searchableText = o.optBoolean("searchable", false);
            r.documentTitle = o.optString("title", "");
            r.source = o.optString("source", "");
            r.summary = o.optString("summary", "");
            r.analyzedAt = o.optLong("analyzedAt", 0);
            JSONArray a = o.optJSONArray("entries");
            if (a != null) for (int i=0;i<a.length();i++) {
                JSONObject e=a.optJSONObject(i); if(e==null)continue;
                r.entries.add(new Entry(e.optString("title"),e.optInt("page",-1),e.optInt("level",0),e.optString("source",r.source)));
            }
            return r;
        } catch (Throwable ignored) { return null; }
    }

    public static void invalidate(Context c, String bookId, String uri) {
        c.getSharedPreferences(PREF,0).edit().remove(key(bookId,uri)).apply();
    }

    public static void analyze(Context c, String bookId, String uri, boolean force, Callback callback) {
        Context app = c.getApplicationContext();
        if (!force) {
            Result hit = cached(app,bookId,uri);
            if (hit != null) { deliver(callback, hit); return; }
        } else invalidate(app,bookId,uri);

        final String runKey = key(bookId,uri);
        synchronized (LOCK) {
            ArrayList<Callback> waiters = WAITERS.get(runKey);
            if (waiters == null) { waiters = new ArrayList<>(); WAITERS.put(runKey,waiters); }
            if (callback != null) waiters.add(callback);
            if (RUNNING.contains(runKey)) return;
            RUNNING.add(runKey);
        }

        new Thread(() -> {
            Result result = doAnalyze(app, uri);
            if (result.error == null) save(app,bookId,uri,result);
            ArrayList<Callback> callbacks;
            synchronized (LOCK) {
                RUNNING.remove(runKey);
                callbacks = WAITERS.remove(runKey);
            }
            if (callbacks != null) for (Callback cb:callbacks) deliver(cb,result);
        }, "pdf-book-analysis").start();
    }

    private static Result doAnalyze(Context context, String uriString) {
        Result r = new Result(); r.analyzedAt = System.currentTimeMillis();
        PDDocument doc = null; InputStream in = null;
        try {
            PDFBoxResourceLoader.init(context);
            in = AppFileResolver.openInputStream(context, uriString);
            if (in == null) throw new IllegalStateException("PDF nicht lesbar");
            MemoryUsageSetting mem = MemoryUsageSetting.setupMixed(12L * 1024L * 1024L).setTempDir(context.getCacheDir());
            doc = PDDocument.load(in, mem);
            r.pageCount = doc.getNumberOfPages();
            try { if (doc.getDocumentInformation()!=null && doc.getDocumentInformation().getTitle()!=null) r.documentTitle=doc.getDocumentInformation().getTitle().trim(); } catch(Throwable ignored){}

            // Embedded bookmarks are the most reliable TOC and already carry exact PDF destinations.
            ArrayList<Entry> embedded = new ArrayList<>();
            try {
                PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
                if (outline != null) walkOutline(doc, outline.getFirstChild(), 0, embedded, 0);
            } catch (Throwable ignored) { }
            if (embedded.size() >= 2) {
                r.entries.addAll(embedded);
                r.source = "PDF-Lesezeichen";
                r.searchableText = true; // a structured PDF is normally searchable; not a hard guarantee.
                r.summary = r.pageCount + " Seiten · " + embedded.size() + " Kapitel/Lesezeichen erkannt";
                return r;
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            ArrayList<Heading> headings = new ArrayList<>();
            ArrayList<TocCandidate> toc = new ArrayList<>();
            HashMap<Integer,Integer> offsetVotes = new HashMap<>();
            boolean tocZone = false; int tocUntil = -1; int pagesWithText = 0;

            for (int page=0; page<r.pageCount; page++) {
                String text = extractPage(stripper,doc,page);
                if (text == null || text.trim().isEmpty()) continue;
                pagesWithText++; r.searchableText = true;
                ArrayList<String> lines = lines(text);
                if (lines.isEmpty()) continue;

                if (page < 90) {
                    for (String line:lines) if (looksLikeTocHeader(line)) { tocZone=true; tocUntil=Math.max(tocUntil,page+14); break; }
                    if (tocZone && page <= tocUntil) {
                        for (String line:lines) {
                            TocCandidate c = parseTocLine(line);
                            if (c != null && toc.size() < 700) toc.add(c);
                        }
                    }
                }

                Integer printed = detectPrintedPage(lines);
                if (printed != null && printed > 0 && printed < 5000) {
                    int off = page - (printed - 1);
                    if (Math.abs(off) <= 120) offsetVotes.put(off, offsetVotes.getOrDefault(off,0)+1);
                }

                Heading h = detectHeading(lines,page);
                if (h != null && headings.size() < 1200) headings.add(h);
            }

            int offset = bestOffset(offsetVotes);
            ArrayList<Entry> generated = new ArrayList<>();
            if (toc.size() >= 3) {
                for (TocCandidate c:toc) {
                    int predicted = clamp(c.printedPage - 1 + offset,0,Math.max(0,r.pageCount-1));
                    Heading best = bestHeading(c.title, headings, predicted);
                    int pageIndex = best != null ? best.pageIndex : predicted;
                    String title = cleanTitle(c.title);
                    if (title.length() < 3) continue;
                    generated.add(new Entry(title,pageIndex,c.level,"auto-toc"));
                }
                generated = dedupe(generated, 450);
                if (generated.size() >= 3) r.source = "Automatisch erkanntes Inhaltsverzeichnis";
            }

            if (generated.size() < 3) {
                generated.clear();
                for (Heading h:headings) generated.add(new Entry(h.title,h.pageIndex,h.level,"auto-heading"));
                generated = dedupe(generated, 450);
                if (generated.size() >= 2) r.source = "Automatisch erkannte Kapitel";
            }

            r.entries.addAll(generated);
            if (r.source.isEmpty()) r.source = r.searchableText ? "Textanalyse" : "Keine Textschicht";
            if (!r.searchableText) r.summary = r.pageCount + " Seiten · keine auslesbare Textschicht erkannt";
            else if (r.entries.isEmpty()) r.summary = r.pageCount + " Seiten · Text erkannt, aber keine sicheren Kapitelüberschriften";
            else r.summary = r.pageCount + " Seiten · " + r.entries.size() + " Sprungziele automatisch erkannt";
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + (t.getMessage()==null?"":": "+t.getMessage());
        } finally {
            try { if (doc != null) doc.close(); } catch(Exception ignored){}
            try { if (in != null) in.close(); } catch(Exception ignored){}
        }
        return r;
    }

    private static String extractPage(PDFTextStripper stripper, PDDocument doc, int page) {
        try {
            stripper.setStartPage(page+1); stripper.setEndPage(page+1);
            String s=stripper.getText(doc);
            if (s != null && s.length() > 16000) s=s.substring(0,16000);
            return s;
        } catch (Throwable ignored) { return ""; }
    }

    private static ArrayList<String> lines(String text) {
        ArrayList<String> out=new ArrayList<>();
        for(String raw:text.replace('\r','\n').split("\\n+")) {
            String s=raw.replaceAll("\\s+"," ").trim();
            if(!s.isEmpty())out.add(s);
            if(out.size()>=120)break;
        }
        return out;
    }

    private static boolean looksLikeTocHeader(String s) {
        String n=norm(s);
        return n.equals("inhaltsverzeichnis") || n.contains("inhaltsverzeichnis") || n.equals("contents") || n.contains("table of contents") || n.equals("gliederung");
    }

    private static TocCandidate parseTocLine(String line) {
        if (line == null || line.length()<4 || line.length()>180) return null;
        Matcher m=ENDING_PAGE.matcher(line.trim()); if(!m.matches())return null;
        String title=m.group(1).replaceAll("[.·•…]+$","").trim();
        if(title.length()<3 || title.matches("^[0-9 .:/-]+$"))return null;
        int p; try{p=Integer.parseInt(m.group(2));}catch(Exception e){return null;}
        if(p<=0||p>5000)return null;
        int level=sectionLevel(title);
        return new TocCandidate(title,p,level);
    }

    private static Heading detectHeading(ArrayList<String> lines,int page) {
        int limit=Math.min(6,lines.size());
        for(int i=0;i<limit;i++){
            String s=lines.get(i).trim(); if(s.length()<3||s.length()>130)continue;
            if(PLAIN_PAGE.matcher(norm(s)).matches())continue;
            String n=norm(s);
            if(n.startsWith("kapitel ")||n.startsWith("chapter ")||n.startsWith("lektion ")||n.startsWith("modul ")||n.startsWith("teil ")||n.startsWith("abschnitt ")||n.startsWith("thema ")||n.startsWith("unit ")) return new Heading(cleanTitle(s),page,0);
            Matcher nm=NUMBERED_HEADING.matcher(s); if(nm.matches() && letterCount(nm.group(2))>=3) return new Heading(cleanTitle(s),page,sectionLevel(nm.group(1)));
            if(i<=2 && s.length()<=85 && letterCount(s)>=5 && upperRatio(s)>=.76f && wordCount(s)<=12) return new Heading(cleanTitle(s),page,1);
        }
        return null;
    }

    private static Integer detectPrintedPage(ArrayList<String> lines) {
        int n=lines.size(); int[] idx={0,1,Math.max(0,n-2),Math.max(0,n-1)};
        for(int i:idx){if(i<0||i>=n)continue;Matcher m=PLAIN_PAGE.matcher(norm(lines.get(i)));if(m.matches())try{return Integer.parseInt(m.group(1));}catch(Exception ignored){}}
        return null;
    }

    private static Heading bestHeading(String tocTitle, ArrayList<Heading> headings, int predicted) {
        String a=normWords(tocTitle); if(a.length()<3)return null; Heading best=null; float score=.0f;
        for(Heading h:headings){
            int dist=Math.abs(h.pageIndex-predicted); if(dist>80 && headings.size()>80)continue;
            float s=similarity(a,normWords(h.title));
            if(dist<=12)s+=.12f; else if(dist<=30)s+=.05f;
            if(s>score){score=s;best=h;}
        }
        return score>=.52f?best:null;
    }

    private static float similarity(String a,String b) {
        if(a.isEmpty()||b.isEmpty())return 0f;
        if(a.equals(b))return 1f;
        if(a.contains(b)||b.contains(a))return .86f;
        Set<String> aa=new HashSet<>(),bb=new HashSet<>();Collections.addAll(aa,a.split(" "));Collections.addAll(bb,b.split(" "));aa.removeIf(x->x.length()<3);bb.removeIf(x->x.length()<3);if(aa.isEmpty()||bb.isEmpty())return 0f;int common=0;for(String x:aa)if(bb.contains(x))common++;return common/(float)Math.max(aa.size(),bb.size());
    }

    private static int bestOffset(HashMap<Integer,Integer> votes) {
        int best=0,count=0;for(Map.Entry<Integer,Integer> e:votes.entrySet())if(e.getValue()>count){best=e.getKey();count=e.getValue();}return count>=2?best:0;
    }

    private static int walkOutline(PDDocument doc, PDOutlineItem item, int level, ArrayList<Entry> out, int seen) {
        PDOutlineItem cur=item;
        while(cur!=null && seen<900){
            try{PDPage p=cur.findDestinationPage(doc);int idx=p==null?-1:doc.getPages().indexOf(p);String t=cur.getTitle()==null?"":cur.getTitle().trim();if(!t.isEmpty()&&idx>=0){out.add(new Entry(t,idx,Math.min(5,level),"outline"));seen++;}if(cur.getFirstChild()!=null)seen=walkOutline(doc,cur.getFirstChild(),level+1,out,seen);}catch(Throwable ignored){}
            cur=cur.getNextSibling();
        }
        return seen;
    }

    private static ArrayList<Entry> dedupe(ArrayList<Entry> in,int max) {
        ArrayList<Entry> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        Collections.sort(in,(a,b)->{int d=Integer.compare(a.pageIndex,b.pageIndex);return d!=0?d:a.title.compareToIgnoreCase(b.title);});
        for(Entry e:in){if(e.pageIndex<0)continue;String k=e.pageIndex+"|"+norm(e.title);if(seen.add(k)){out.add(e);if(out.size()>=max)break;}}
        return out;
    }

    private static void save(Context c,String bookId,String uri,Result r){
        try{JSONObject o=new JSONObject();o.put("uri",safe(uri));o.put("pageCount",r.pageCount);o.put("searchable",r.searchableText);o.put("title",r.documentTitle);o.put("source",r.source);o.put("summary",r.summary);o.put("analyzedAt",r.analyzedAt);JSONArray a=new JSONArray();for(Entry e:r.entries){JSONObject x=new JSONObject();x.put("title",e.title);x.put("page",e.pageIndex);x.put("level",e.level);x.put("source",e.source);a.put(x);}o.put("entries",a);c.getSharedPreferences(PREF,0).edit().putString(key(bookId,uri),o.toString()).apply();}catch(Throwable ignored){}
    }

    private static void deliver(Callback cb,Result r){if(cb==null)return;new Handler(Looper.getMainLooper()).post(()->cb.done(r));}
    private static String key(String bookId,String uri){String id=bookId==null||bookId.trim().isEmpty()?Integer.toHexString(safe(uri).hashCode()):bookId;return "book_"+id.replaceAll("[^a-zA-Z0-9._-]","_");}
    private static String safe(String s){return s==null?"":s;}
    private static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private static int sectionLevel(String s){Matcher m=Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(s.trim());if(!m.find())return 0;int dots=0;for(char c:m.group(1).toCharArray())if(c=='.')dots++;return Math.min(4,dots);}
    private static int letterCount(String s){int n=0;for(char c:s.toCharArray())if(Character.isLetter(c))n++;return n;}
    private static int wordCount(String s){String t=s.trim();return t.isEmpty()?0:t.split("\\s+").length;}
    private static float upperRatio(String s){int l=0,u=0;for(char c:s.toCharArray())if(Character.isLetter(c)){l++;if(Character.isUpperCase(c))u++;}return l==0?0:u/(float)l;}
    private static String cleanTitle(String s){return s.replaceAll("\\s+"," ").replaceAll("[.·•…]+$","").trim();}
    private static String norm(String s){String n=Normalizer.normalize(s==null?"":s,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);return n.replaceAll("[^a-z0-9äöüß ]+"," ").replaceAll("\\s+"," ").trim();}
    private static String normWords(String s){return norm(s).replaceAll("\\b(der|die|das|und|oder|von|vom|zur|zum|the|and|of|a|an)\\b"," ").replaceAll("\\s+"," ").trim();}

    private static final class Heading { final String title;final int pageIndex,level;Heading(String t,int p,int l){title=t;pageIndex=p;level=l;} }
    private static final class TocCandidate { final String title;final int printedPage,level;TocCandidate(String t,int p,int l){title=t;printedPage=p;level=l;} }
}
