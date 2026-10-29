package de.millione.schulranzen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.view.MotionEvent;
import android.view.View;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.input.motionprediction.MotionEventPredictor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V15 paper engine: offline first, multi-page, stylus-only ink, adaptive zoom/pan,
 * true partial eraser, lasso, explicit line/ruler mode, unsnapped handwriting, text objects,
 * page backgrounds for scanned worksheets, page titles/TOC and PDF export.
 */
public class NotebookView extends View {
    public static final float PAGE_W = 1240f;
    public static final float PAGE_H = 1754f;
    private static final int MAGIC = 0x53423135; // SB15
    private static final int V14_MAGIC = 0x53423134; // SB14
    private static final int V13_MAGIC = 0x53423133; // SB13
    private static final int V9_MAGIC = 0x53423931; // SB91
    private static final int V8_MAGIC = 0x53423831; // SB81
    private static final int HISTORY_LIMIT = 40;
    private static final long SAVE_DEBOUNCE_MS = 650L;

    public interface OnPageListener {
        void changed(int page, int count, boolean bookmarked);
        void overviewRequested();
        void textRequested(float pageX, float pageY);
        void saved();
    }

    public interface OnInkListener { void inkChanged(); }

    public static final class InkPointData {
        public final float x, y; public final long t;
        InkPointData(float x, float y, long t){this.x=x;this.y=y;this.t=t;}
    }
    public static final class InkStrokeData {
        public final ArrayList<InkPointData> points = new ArrayList<>();
        long endedAt;
    }
    public static final class InkSnapshot {
        public final ArrayList<InkStrokeData> strokes = new ArrayList<>();
        public int pageIndex; public float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE; public long lastEventTime;
        /** True when this snapshot was collected from inside an intentionally drawn enclosing loop. */
        public boolean circled=false;
        public float centerY(){return (minY+maxY)*.5f;}
    }

    private final Context context;
    private final String documentId;
    private final ArrayList<Page> pages = new ArrayList<>();
    private final ArrayList<Page> pageTrash = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final File file;
    private final ArrayDeque<PageSnapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<PageSnapshot> redo = new ArrayDeque<>();
    private final HashMap<String, Bitmap> backgroundCache = new HashMap<>();

    // V15 low-latency path: committed ink is cached once in page coordinates.
    // During a pen stroke only the live stroke is redrawn; the whole page is not.
    private static final float INK_CACHE_SCALE = 2.0f;
    private Bitmap inkCache;
    private Canvas inkCacheCanvas;
    private int inkCachePage = -1;
    private int inkCacheRevision = -1;
    private final Paint inkCachePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint inkCacheBlitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    // Disk I/O is deliberately removed from the stylus-up hot path.
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schulranzen-save");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Runnable delayedSave = this::snapshotAndSaveAsync;

    private Stroke active;
    private OnPageListener listener;
    private OnInkListener inkListener;
    private final ArrayList<InkStrokeData> recentInk = new ArrayList<>();
    private InkStrokeData recentActiveInk;
    private int page = 0;
    public int color = Color.BLACK;
    public int tool = 1; // 0 pencil, 1 ballpoint, 2 fountain, 3 marker, 4 eraser, 5 lasso, 6 ruler, 7 text
    public int eraserMode = 0; // 0 partial, 1 whole stroke
    private float penWidth = 4.2f;
    private float markerWidth = 18f;
    private int pressureMode = 2; // 0 off, 1 soft, 2 natural, 3 strong
    private boolean tiltEnabled = true;

    private float zoom = 1f;
    private float offsetX = 0f, offsetY = 0f;
    private float downX, downY, lastX, lastY;
    private boolean stylusNear = false;
    private boolean fingerPanning = false;
    private boolean erasingGesture = false;
    private float lastErasePageX = Float.NaN, lastErasePageY = Float.NaN;
    private final Paint eraseCachePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long stylusDownTime;
    private MotionEventPredictor motionPredictor;
    private boolean predictedValid=false;
    private float predictedX,predictedY,predictedPressure=1f,predictedTilt=0f;

    private boolean pinching = false;
    private float pinchStartDistance;
    private float pinchStartZoom;
    private float pinchAnchorPageX, pinchAnchorPageY;

    private boolean fiveFingerActive = false;
    private boolean fiveFingerTriggered = false;
    private float fiveStartSpan = 0f;

    private boolean threeFingerActive = false;
    private float threeDownX;

    private final ArrayList<Pt> lassoPath = new ArrayList<>();
    private final HashSet<Integer> selectedStrokes = new HashSet<>();
    private RectF selectionBounds = null;
    private boolean movingSelection = false;
    private float moveLastX, moveLastY;

    private Bitmap currentBackground;
    private String currentBackgroundPath = "";

    // Pasted book snippets are true page objects, not flattened backgrounds.
    // Their geometry is stored in page coordinates, so zoom never shifts them.
    private int selectedImage = -1;
    private boolean imageMoving = false;
    private boolean imageResizing = false;
    private float imageTouchStartX, imageTouchStartY;
    private float imageStartX, imageStartY, imageStartW, imageStartH;

    public NotebookView(Context c) { this(c, "legacy-main"); }

    public NotebookView(Context c, String id) {
        super(c);
        context = c.getApplicationContext();
        documentId = (id == null || id.trim().isEmpty()) ? "legacy-main" : id;
        android.content.SharedPreferences themePrefs=c.getSharedPreferences("bag",0);String tm=themePrefs.getString("theme_mode","auto");boolean dm="dark".equals(tm)||("auto".equals(tm)&&(c.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);setBackgroundColor(dm?0xff111318:0xffeeeeeb);
        file = new File(c.getFilesDir(), "notebook-v15-" + safe(documentId) + ".bin");
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);
        if (!loadV15()) {
            if (!migrateDocumentV14(c)) {
                if (!migrateDocumentV13(c) && !migrateDocumentV9(c) && "legacy-main".equals(documentId)) {
                    if (!migrateV8(c)) migrateV7(c);
                    if (pages.isEmpty()) migrateV3(c);
                }
            }
        }
        if (pages.isEmpty()) pages.add(new Page());
        setFocusable(true);
        try{motionPredictor=MotionEventPredictor.newInstance(this);}catch(Throwable ignored){motionPredictor=null;}
    }

    private static String safe(String id){ return id.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    public String getDocumentId(){ return documentId; }
    public void setListener(OnPageListener l) { listener = l; notifyPage(); }
    public void setInkListener(OnInkListener l) { inkListener = l; }
    public void clearRecentInk(){ recentInk.clear(); recentActiveInk=null; }
    public InkSnapshot getRecentInkSnapshot(long maxAgeMs){
        long now=android.os.SystemClock.uptimeMillis(); pruneRecentInk(now,maxAgeMs); if(recentInk.isEmpty())return null;
        InkSnapshot out=new InkSnapshot(); out.pageIndex=page;
        for(InkStrokeData src:recentInk){InkStrokeData dst=new InkStrokeData();dst.endedAt=src.endedAt;for(InkPointData q:src.points){InkPointData cp=new InkPointData(q.x,q.y,q.t);dst.points.add(cp);out.minX=Math.min(out.minX,q.x);out.minY=Math.min(out.minY,q.y);out.maxX=Math.max(out.maxX,q.x);out.maxY=Math.max(out.maxY,q.y);out.lastEventTime=Math.max(out.lastEventTime,q.t);}if(dst.points.size()>1)out.strokes.add(dst);}
        return out.strokes.isEmpty()?null:out;
    }
    /** Recent arithmetic is grouped by time and writing line so unrelated old ink does not poison live recognition. */
    public InkSnapshot getRecentMathInkSnapshot(long maxAgeMs){
        long now=android.os.SystemClock.uptimeMillis();pruneRecentInk(now,maxAgeMs);if(recentInk.isEmpty())return null;
        int last=recentInk.size()-1;InkStrokeData newest=recentInk.get(last);float targetY=strokeCenterY(newest),minY=targetY,maxY=targetY;long newestEnd=newest.endedAt;
        ArrayList<InkStrokeData> picked=new ArrayList<>();
        for(int i=last;i>=0;i--){InkStrokeData st=recentInk.get(i);if(newestEnd-st.endedAt>4800)break;float cy=strokeCenterY(st);float tol=Math.max(150f,(maxY-minY)+115f);if(picked.isEmpty()||Math.abs(cy-targetY)<=tol){picked.add(0,st);minY=Math.min(minY,strokeMinY(st));maxY=Math.max(maxY,strokeMaxY(st));targetY=(minY+maxY)*.5f;}}
        InkSnapshot out=new InkSnapshot();out.pageIndex=page;
        for(InkStrokeData src:picked){InkStrokeData dst=new InkStrokeData();dst.endedAt=src.endedAt;for(InkPointData q:src.points){InkPointData cp=new InkPointData(q.x,q.y,q.t);dst.points.add(cp);out.minX=Math.min(out.minX,q.x);out.minY=Math.min(out.minY,q.y);out.maxX=Math.max(out.maxX,q.x);out.maxY=Math.max(out.maxY,q.y);out.lastEventTime=Math.max(out.lastEventTime,q.t);}if(dst.points.size()>1)out.strokes.add(dst);}
        return out.strokes.isEmpty()?null:out;
    }
    /** Current committed-ink revision. Useful for de-duplicating circle-to-calculate requests. */
    public int getCurrentInkRevision(){return valid(page)?pages.get(page).inkRevision:0;}

    /**
     * If the most recently committed pen stroke is a deliberately drawn large closed loop,
     * return every arithmetic ink stroke enclosed by that loop. The loop itself is excluded.
     * This is geometry based and does not alter/snap the user's handwriting.
     */
    public InkSnapshot getCircledMathInkSnapshot(){
        if(!valid(page))return null;Page pg=pages.get(page);if(pg.strokes.size()<2)return null;
        Stroke ring=pg.strokes.get(pg.strokes.size()-1);if(ring.tool<0||ring.tool>2||!looksLikeIntentionalLoop(ring))return null;
        RectF rb=strokeBounds(ring);float cx=rb.centerX(),cy=rb.centerY(),rx=Math.max(1f,rb.width()*.5f),ry=Math.max(1f,rb.height()*.5f);
        InkSnapshot out=new InkSnapshot();out.pageIndex=page;out.circled=true;out.lastEventTime=pg.inkRevision;long base=android.os.SystemClock.uptimeMillis()-4000;int seq=0;
        for(int i=0;i<pg.strokes.size()-1;i++){Stroke st=pg.strokes.get(i);if(st.pts.size()<2)continue;if(!(st.tool>=0&&st.tool<=2)&&st.tool!=6)continue;
            int inside=0;float sx=0f,sy=0f;for(Pt q:st.pts){sx+=q.x;sy+=q.y;float nx=(q.x-cx)/rx,ny=(q.y-cy)/ry;if(nx*nx+ny*ny<=1.10f)inside++;}
            float scx=sx/st.pts.size(),scy=sy/st.pts.size(),cnx=(scx-cx)/rx,cny=(scy-cy)/ry;boolean centerInside=cnx*cnx+cny*cny<=.92f;
            if(!centerInside&&inside<Math.max(2,(int)Math.ceil(st.pts.size()*.58f)))continue;
            InkStrokeData dst=new InkStrokeData();dst.endedAt=base+seq*80L;int pi=0;for(Pt q:st.pts){long t=base+seq*80L+pi*3L;dst.points.add(new InkPointData(q.x,q.y,t));out.minX=Math.min(out.minX,q.x);out.minY=Math.min(out.minY,q.y);out.maxX=Math.max(out.maxX,q.x);out.maxY=Math.max(out.maxY,q.y);pi++;}if(dst.points.size()>1){out.strokes.add(dst);seq++;}
        }
        return out.strokes.isEmpty()?null:out;
    }

    private static RectF strokeBounds(Stroke s){float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;for(Pt p:s.pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}return new RectF(minX,minY,maxX,maxY);}

    /** Strict loop detector: rejects triangles/rectangles and tiny digit loops while accepting hand-drawn circles/ovals. */
    private static boolean looksLikeIntentionalLoop(Stroke s){
        if(s==null||s.pts.size()<18)return false;RectF b=strokeBounds(s);float w=b.width(),h=b.height();if(w<105f||h<70f)return false;float aspect=w/Math.max(1f,h);if(aspect<.32f||aspect>3.1f)return false;
        Pt first=s.pts.get(0),last=s.pts.get(s.pts.size()-1);float diag=(float)Math.hypot(w,h);if(distance(first.x,first.y,last.x,last.y)>Math.max(38f,diag*.22f))return false;
        float len=0f;for(int i=1;i<s.pts.size();i++)len+=distance(s.pts.get(i-1).x,s.pts.get(i-1).y,s.pts.get(i).x,s.pts.get(i).y);float a=w*.5f,bb=h*.5f;float perimeter=(float)(Math.PI*(3*(a+bb)-Math.sqrt((3*a+bb)*(a+3*bb))));if(perimeter<=1f||len/perimeter<.62f||len/perimeter>1.75f)return false;
        float cx=b.centerX(),cy=b.centerY(),rx=Math.max(1f,a),ry=Math.max(1f,bb),residual=0f;int samples=0,bins=0;
        for(int i=0;i<s.pts.size();i+=Math.max(1,s.pts.size()/80)){Pt q=s.pts.get(i);float nx=(q.x-cx)/rx,ny=(q.y-cy)/ry;float r=(float)Math.sqrt(nx*nx+ny*ny);if(r<.42f||r>1.58f)return false;residual+=Math.abs(r-1f);samples++;double ang=Math.atan2(ny,nx);if(ang<0)ang+=Math.PI*2;int bin=Math.min(11,(int)(ang/(Math.PI*2)*12));bins|=1<<bin;}
        int covered=Integer.bitCount(bins);return samples>=12&&covered>=10&&residual/Math.max(1,samples)<.17f;
    }

    private static float strokeCenterY(InkStrokeData s){return(strokeMinY(s)+strokeMaxY(s))*.5f;}
    private static float strokeMinY(InkStrokeData s){float v=Float.MAX_VALUE;for(InkPointData p:s.points)v=Math.min(v,p.y);return v==Float.MAX_VALUE?0:v;}
    private static float strokeMaxY(InkStrokeData s){float v=-Float.MAX_VALUE;for(InkPointData p:s.points)v=Math.max(v,p.y);return v==-Float.MAX_VALUE?0:v;}
    public float[] pageToLocal(float px,float py){float s=scale();return new float[]{baseX(s)+offsetX+px*s,baseY(s)+offsetY+py*s};}
    private void pruneRecentInk(long now,long maxAgeMs){for(int i=recentInk.size()-1;i>=0;i--)if(now-recentInk.get(i).endedAt>maxAgeMs)recentInk.remove(i);}
    public int getCurrentPageIndex() { return page; }
    public int getPageCount() { return pages.size(); }
    public boolean isBookmarked(int index) { return valid(index) && pages.get(index).bookmark; }
    public boolean isLocked(int index) { return valid(index) && pages.get(index).locked; }
    public int getPaper(int index) { return valid(index) ? pages.get(index).paper : 0; }
    public String getPageTitle(int index) { return valid(index) ? pages.get(index).title : ""; }
    public String getPlainText(int index) { if(!valid(index))return "";StringBuilder b=new StringBuilder();for(TextEntry t:pages.get(index).texts){if(t.text!=null&&!t.text.trim().isEmpty()){if(b.length()>0)b.append("\n");b.append(t.text.trim());}}return b.toString(); }
    public String getBackgroundPath(int index){ return valid(index) ? pages.get(index).backgroundPath : ""; }
    public int getTrashCount(){ return pageTrash.size(); }
    public float getPenWidth(){ return penWidth; }
    public float getMarkerWidth(){ return markerWidth; }
    public void setPressureMode(int mode){pressureMode=Math.max(0,Math.min(3,mode));invalidateInkCache();requestFrame();}
    public int getPressureMode(){return pressureMode;}
    public void setTiltEnabled(boolean enabled){tiltEnabled=enabled;invalidateInkCache();requestFrame();}
    public boolean isTiltEnabled(){return tiltEnabled;}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}

    public boolean hasSelectedImage(){ return selectedImage>=0 && valid(page) && selectedImage<pages.get(page).images.size(); }
    public boolean selectedImageAnchored(){ return hasSelectedImage() && pages.get(page).images.get(selectedImage).anchored; }

    /** Insert an image from the app clipboard onto the current sheet and select it for placement. */
    public boolean insertImage(String path){
        if(path==null||path.trim().isEmpty()||!new File(path).exists()||pages.get(page).locked)return false;
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);
        if(o.outWidth<=0||o.outHeight<=0)return false;
        pushHistory();
        ImageObject im=new ImageObject();im.path=path;
        float maxW=PAGE_W*.68f,maxH=PAGE_H*.42f,ratio=Math.min(maxW/o.outWidth,maxH/o.outHeight);
        ratio=Math.max(.08f,ratio);im.w=o.outWidth*ratio;im.h=o.outHeight*ratio;
        im.x=(PAGE_W-im.w)/2f;im.y=Math.max(120f,(PAGE_H-im.h)/2f);
        pages.get(page).images.add(im);selectedImage=pages.get(page).images.size()-1;
        save();invalidate();return true;
    }

    public void toggleSelectedImageAnchor(){
        if(!hasSelectedImage())return;pushHistory();ImageObject im=pages.get(page).images.get(selectedImage);im.anchored=!im.anchored;
        save();invalidate();
    }
    public void deleteSelectedImage(){
        if(!hasSelectedImage())return;pushHistory();pages.get(page).images.remove(selectedImage);selectedImage=-1;save();invalidate();
    }
    public void clearImageSelection(){selectedImage=-1;imageMoving=imageResizing=false;invalidate();}

    private boolean valid(int index){ return index >= 0 && index < pages.size(); }

    private void notifyPage() {
        if (listener != null && !pages.isEmpty()) listener.changed(page, pages.size(), pages.get(page).bookmark);
    }

    public void setTool(int t) {
        tool = Math.max(0, Math.min(7, t));
        if (tool != 5) clearLassoSelection();
        invalidate();
    }

    public void setPenWidth(float w){ penWidth = clamp(w, 1.1f, 18f); }
    public void setMarkerWidth(float w){ markerWidth = clamp(w, 8f, 54f); }

    public void setEraserMode(int mode) {
        eraserMode = mode == 1 ? 1 : 0;
        tool = 4;
        clearLassoSelection();
        invalidate();
    }

    public void setPaper(int kind) {
        if (pages.isEmpty()) return;
        pushHistory();
        pages.get(page).paper = Math.max(0, Math.min(7, kind));
        save(); invalidate();
    }

    public void setPageTitle(int index,String title){
        if(!valid(index))return; pages.get(index).title = title == null ? "" : title.trim(); save(); notifyPage();
    }

    public void setLocked(int index, boolean locked){ if(valid(index)){pages.get(index).locked=locked;save();notifyPage();invalidate();} }

    public void setBackgroundPath(int index,String path){
        if(!valid(index))return; pushHistory(); pages.get(index).backgroundPath=path==null?"":path; clearBackgroundCache(); save(); invalidate();
    }

    public void addBackgroundPages(ArrayList<String> paths, boolean replaceFirstBlank){
        if(paths==null||paths.isEmpty())return;
        if(replaceFirstBlank && pages.size()==1 && pages.get(0).isEmpty()) {
            pages.get(0).backgroundPath=paths.get(0);
            for(int i=1;i<paths.size();i++){Page p=new Page();p.backgroundPath=paths.get(i);pages.add(p);}
        } else {
            for(String path:paths){Page p=new Page();p.backgroundPath=path;pages.add(p);}
        }
        page=Math.max(0,pages.size()-paths.size()); clearHistory(); clearBackgroundCache(); save(); notifyPage(); invalidate();
    }

    public void addText(float x,float y,String text){
        if(text==null||text.trim().isEmpty()||pages.get(page).locked)return; pushHistory(); TextEntry e=new TextEntry();e.x=x;e.y=y;e.text=text.trim();e.color=color;e.size=42f;pages.get(page).texts.add(e);save();invalidate();
    }

    public void toggleBookmark() { toggleBookmark(page); }
    public void toggleBookmark(int index) { if(valid(index)){pages.get(index).bookmark=!pages.get(index).bookmark;save();notifyPage();invalidate();} }

    public void addPage() {
        Page p=new Page();
        Page cur=pages.get(page); p.paper=cur.paper;
        pages.add(p); page=pages.size()-1; clearHistory(); clearBackgroundCache(); resetView(); save(); notifyPage();
    }

    public void addPageFromTemplate(int paper,String bg){
        Page p=new Page();p.paper=Math.max(0,Math.min(7,paper));p.backgroundPath=bg==null?"":bg;pages.add(p);page=pages.size()-1;clearHistory();clearBackgroundCache();resetView();save();notifyPage();
    }

    public void duplicatePage(int index) {
        if (!valid(index)) return;
        pages.add(index + 1, pages.get(index).deepCopy()); page=index+1; clearHistory(); clearBackgroundCache(); resetView(); save(); notifyPage();
    }

    public void movePage(int from,int to){
        if(!valid(from))return; to=Math.max(0,Math.min(to,pages.size()-1)); if(from==to)return; Page p=pages.remove(from); pages.add(to,p); page=to; clearHistory(); save();notifyPage();invalidate();
    }

    public void deletePage(int index) {
        if (!valid(index)) return;
        pageTrash.add(pages.get(index).deepCopy());
        if (pages.size() == 1) { pages.set(0, new Page()); page=0; }
        else { pages.remove(index); if(page>=pages.size())page=pages.size()-1; else if(index<page)page--; }
        clearHistory(); clearBackgroundCache(); resetView(); save(); notifyPage();
    }

    public boolean restoreLastDeletedPage(){
        if(pageTrash.isEmpty())return false; Page p=pageTrash.remove(pageTrash.size()-1); pages.add(Math.min(page+1,pages.size()),p);page=Math.min(page+1,pages.size()-1);clearHistory();clearBackgroundCache();save();notifyPage();invalidate();return true;
    }

    public void deletePages(ArrayList<Integer> indices){
        if(indices==null||indices.isEmpty())return;
        indices.sort((a,b)->Integer.compare(b,a));
        for(int idx:indices) if(valid(idx)){pageTrash.add(pages.get(idx).deepCopy());pages.remove(idx);}
        if(pages.isEmpty())pages.add(new Page());page=Math.min(page,pages.size()-1);clearHistory();clearBackgroundCache();save();notifyPage();invalidate();
    }

    public void goToPage(int index) { if(valid(index)){page=index;clearRecentInk();clearHistory();clearBackgroundCache();invalidateInkCache();resetView();notifyPage();} }
    public void nextPage(){ if(page<pages.size()-1){page++;clearRecentInk();clearHistory();clearBackgroundCache();resetView();notifyPage();}else addPage(); }
    public void previousPage(){ if(page>0){page--;clearRecentInk();clearHistory();clearBackgroundCache();resetView();notifyPage();} }

    public void resetView(){ zoom=1f;offsetX=offsetY=0f;selectedImage=-1;imageMoving=imageResizing=false;invalidateInkCache();requestFrame(); }

    public void undo(){ if(undo.isEmpty())return;redo.push(new PageSnapshot(page,pages.get(page).deepCopy()));PageSnapshot s=undo.pop();if(s.pageIndex==page){Page restored=s.page.deepCopy();restored.inkRevision++;pages.set(page,restored);}invalidateInkCache();clearBackgroundCache();save();notifyPage();requestFrame(); }
    public void redo(){ if(redo.isEmpty())return;undo.push(new PageSnapshot(page,pages.get(page).deepCopy()));PageSnapshot s=redo.pop();if(s.pageIndex==page){Page restored=s.page.deepCopy();restored.inkRevision++;pages.set(page,restored);}invalidateInkCache();clearBackgroundCache();save();notifyPage();requestFrame(); }

    private void clearHistory(){undo.clear();redo.clear();}
    private void pushHistory(){undo.push(new PageSnapshot(page,pages.get(page).deepCopy()));while(undo.size()>HISTORY_LIMIT)undo.removeLast();redo.clear();}

    private float fitScale(){return Math.min((getWidth()-8f)/PAGE_W,(getHeight()-8f)/PAGE_H);}
    private float scale(){return fitScale()*zoom;}
    private float baseX(float s){return(getWidth()-PAGE_W*s)/2f;}
    private float baseY(float s){return(getHeight()-PAGE_H*s)/2f;}
    private float pageX(float sx){float s=scale();return(sx-baseX(s)-offsetX)/s;}
    private float pageY(float sy){float s=scale();return(sy-baseY(s)-offsetY)/s;}
    private boolean insidePage(float px,float py){return px>=0&&px<=PAGE_W&&py>=0&&py<=PAGE_H;}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c); if(pages.isEmpty())return; float s=scale();
        c.save();c.translate(baseX(s)+offsetX,baseY(s)+offsetY);c.scale(s,s);
        // Hardware-friendly paper depth. Avoid Paint shadow layers on the live ink surface.
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x18000000);c.drawRoundRect(7,10,PAGE_W+7,PAGE_H+10,7,7,paint);paint.setColor(Color.WHITE);c.drawRoundRect(0,0,PAGE_W,PAGE_H,5,5,paint);
        Page pg=pages.get(page); drawBackground(c,pg); drawPaper(c,pg.paper,paint);
        for(int i=0;i<pg.images.size();i++)drawImageObject(c,pg.images.get(i),i==selectedImage);
        ensureInkCache(pg);
        if(inkCache!=null)c.drawBitmap(inkCache,null,new RectF(0,0,PAGE_W,PAGE_H),inkCacheBlitPaint);
        // Selection feedback stays dynamic; committed ink itself remains cached.
        if(!selectedStrokes.isEmpty())for(int i:selectedStrokes)if(i>=0&&i<pg.strokes.size())drawStroke(c,pg.strokes.get(i),paint,true);
        for(TextEntry t:pg.texts)drawTextEntry(c,t,paint);
        if(active!=null)drawStroke(c,active,paint,false);
        if(active!=null&&predictedValid&&active.pts.size()>0)drawPredictedSegment(c,active,paint);
        if(!lassoPath.isEmpty())drawLasso(c);
        if(selectionBounds!=null)drawSelection(c,selectionBounds);
        if(pg.bookmark){paint.setColor(0xffffb300);paint.setStyle(Paint.Style.FILL);Path b=new Path();b.moveTo(PAGE_W-78,0);b.lineTo(PAGE_W,0);b.lineTo(PAGE_W,112);b.lineTo(PAGE_W-39,86);b.lineTo(PAGE_W-78,112);b.close();c.drawPath(b,paint);}
        if(pg.locked){paint.setColor(0xaa34312d);paint.setTextSize(34);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.RIGHT);c.drawText("🔒",PAGE_W-24,54,paint);paint.setTypeface(Typeface.DEFAULT);}
        c.restore();
    }

    private void drawImageObject(Canvas c,ImageObject im,boolean selected){
        Bitmap b=loadBitmap(im.path);if(b==null)return;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        RectF r=new RectF(im.x,im.y,im.x+im.w,im.y+im.h);c.drawBitmap(b,null,r,p);
        if(selected){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3f);p.setColor(im.anchored?0xff22a06b:0xff3157d5);c.drawRoundRect(r,8f,8f,p);
            p.setStyle(Paint.Style.FILL);float hs=22f;
            p.setColor(0xff3157d5);c.drawCircle(r.right,r.bottom,hs,p);
            p.setColor(im.anchored?0xff22a06b:0xffffa000);c.drawCircle(r.right,r.top,hs,p);
            p.setColor(0xffd94b4b);c.drawCircle(r.left,r.top,hs,p);
            p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(24f);p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("↘",r.right,r.bottom+8f,p);c.drawText(im.anchored?"✓":"⌁",r.right,r.top+8f,p);c.drawText("×",r.left,r.top+8f,p);
            p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawBackground(Canvas c,Page pg){
        if(pg.backgroundPath==null||pg.backgroundPath.isEmpty())return;
        if(!pg.backgroundPath.equals(currentBackgroundPath)){clearBackgroundCache();currentBackgroundPath=pg.backgroundPath;currentBackground=loadBitmap(pg.backgroundPath);}
        if(currentBackground!=null){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);c.drawBitmap(currentBackground,null,new RectF(0,0,PAGE_W,PAGE_H),p);}
    }

    private Bitmap loadBitmap(String path){
        try{Bitmap b=backgroundCache.get(path);if(b!=null)return b;File f=new File(path);if(!f.exists())return null;BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;b=BitmapFactory.decodeFile(path,o);if(b!=null)backgroundCache.put(path,b);return b;}catch(Exception e){return null;}
    }
    private void clearBackgroundCache(){currentBackground=null;currentBackgroundPath="";for(Bitmap b:backgroundCache.values())try{if(b!=null&&!b.isRecycled())b.recycle();}catch(Exception ignored){}backgroundCache.clear();}

    private static void drawPaper(Canvas c,int kind,Paint p){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1f);p.setColor(0xffd8e1ed);
        if(kind==6)return;
        if(kind==7){
            p.setColor(0xffd6d9df);p.setStrokeWidth(1.4f);float top=180f,left=55f,right=PAGE_W-55f,bottom=PAGE_H-65f;int cols=6,rows=11;float cw=(right-left)/cols,rh=(bottom-top)/rows;for(int i=0;i<=cols;i++)c.drawLine(left+i*cw,top,left+i*cw,bottom,p);for(int r=0;r<=rows;r++)c.drawLine(left,top+r*rh,right,top+r*rh,p);p.setStyle(Paint.Style.FILL);p.setColor(0xff4a5058);p.setTextSize(30);p.setTextAlign(Paint.Align.CENTER);String[] ds={"Zeit","Mo","Di","Mi","Do","Fr"};for(int i=0;i<ds.length;i++)c.drawText(ds[i],left+(i+.5f)*cw,top-42,p);p.setTextSize(22);for(int r=0;r<rows;r++)c.drawText((r+1)+".",left+cw*.5f,top+(r+.62f)*rh,p);p.setTextAlign(Paint.Align.LEFT);return;
        }
        float step=(kind==3?70:35);
        if(kind==1||kind==4){step=kind==4?28:42;for(float y=80;y<PAGE_H;y+=step)c.drawLine(55,y,PAGE_W-55,y,p);p.setColor(0xffffc4c4);c.drawLine(145,0,145,PAGE_H,p);}
        else if(kind==2){p.setStyle(Paint.Style.FILL);for(float y=55;y<PAGE_H;y+=42)for(float x=55;x<PAGE_W;x+=42)c.drawCircle(x,y,1.8f,p);}
        else if(kind==5){p.setColor(0xffd6e1ed);for(float y=35;y<PAGE_H;y+=35)c.drawLine(0,y,PAGE_W,y,p);for(float x=35;x<PAGE_W;x+=35)c.drawLine(x,0,x,PAGE_H,p);p.setColor(0xffb9cadc);p.setStrokeWidth(1.7f);for(float y=175;y<PAGE_H;y+=175)c.drawLine(0,y,PAGE_W,y,p);for(float x=175;x<PAGE_W;x+=175)c.drawLine(x,0,x,PAGE_H,p);}
        else {for(float y=70;y<PAGE_H;y+=step)c.drawLine(0,y,PAGE_W,y,p);for(float x=70;x<PAGE_W;x+=step)c.drawLine(x,0,x,PAGE_H,p);}
    }

    private void requestFrame(){ if(Build.VERSION.SDK_INT>=16)postInvalidateOnAnimation(); else invalidate(); }

    private void invalidateInkCache(){ inkCacheRevision=-1; }

    private void ensureInkCache(Page pg){
        int cacheW=Math.max(1,Math.round(PAGE_W*INK_CACHE_SCALE)),cacheH=Math.max(1,Math.round(PAGE_H*INK_CACHE_SCALE));
        if(inkCache==null||inkCache.getWidth()!=cacheW||inkCache.getHeight()!=cacheH){
            if(inkCache!=null&&!inkCache.isRecycled())inkCache.recycle();
            inkCache=Bitmap.createBitmap(cacheW,cacheH,Bitmap.Config.ARGB_8888);
            inkCache.setDensity(Bitmap.DENSITY_NONE);
            inkCacheCanvas=new Canvas(inkCache);
            // All ink geometry remains in logical page coordinates while the backing bitmap
            // stores twice the pixels. This keeps committed handwriting crisp when zoomed.
            inkCacheCanvas.scale(INK_CACHE_SCALE,INK_CACHE_SCALE);
            inkCachePage=-1;inkCacheRevision=-1;
        }
        if(inkCachePage==page&&inkCacheRevision==pg.inkRevision)return;
        inkCacheCanvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        for(Stroke st:pg.strokes)drawStroke(inkCacheCanvas,st,inkCachePaint,false);
        inkCachePage=page;inkCacheRevision=pg.inkRevision;
    }

    private void appendCommittedStrokeToCache(Page pg,Stroke st){
        if(inkCache!=null&&inkCacheCanvas!=null&&inkCachePage==page&&inkCacheRevision==pg.inkRevision-1){
            drawStroke(inkCacheCanvas,st,inkCachePaint,false);inkCacheRevision=pg.inkRevision;
        } else invalidateInkCache();
    }

    private void drawStroke(Canvas c,Stroke st,Paint p,boolean selected){
        if(st.pts.size()<2)return;
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(st.color);int alpha=255;if(st.tool==3)alpha=92;else if(st.tool==0)alpha=220;p.setAlpha(alpha);
        for(int i=1;i<st.pts.size();i++){Pt a=st.pts.get(i-1),b=st.pts.get(i);float width=(st.width>0?st.width:4.2f)*pointWidthFactor(a,b,st.tool);p.setStrokeWidth(Math.max(.65f,width));c.drawLine(a.x,a.y,b.x,b.y,p);}
        if(selected){p.setAlpha(90);p.setStrokeWidth(Math.max(8f,(st.width>0?st.width:4.2f)+7f));p.setColor(0xff3157d5);Path path=new Path();Pt a=st.pts.get(0);path.moveTo(a.x,a.y);for(int i=1;i<st.pts.size();i++){a=st.pts.get(i);path.lineTo(a.x,a.y);}c.drawPath(path,p);}
        p.setAlpha(255);
    }
    private float pointWidthFactor(Pt a,Pt b,int tool){
        if(tool==6)return 1f;float pressure=(a.pressure+b.pressure)*.5f;float f;
        if(pressureMode==0)f=1f;else if(pressureMode==1)f=.78f+.42f*pressure;else if(pressureMode==3)f=.34f+1.34f*(float)Math.pow(pressure,1.22);else f=.52f+.88f*(float)Math.sqrt(Math.max(0f,pressure));
        if(tool==3)f=.82f+(f-1f)*.38f;
        if(tiltEnabled&&(tool==0||tool==3)){float tilt=Math.max(a.tilt,b.tilt);f*=1f+Math.min(.42f,Math.max(0f,tilt)/(float)(Math.PI/2)*.42f);}
        return clamp(f,.34f,1.8f);
    }

    private void drawPredictedSegment(Canvas c,Stroke st,Paint p){
        if(st==null||st.pts.isEmpty()||!predictedValid)return;Pt a=st.pts.get(st.pts.size()-1);Pt b=new Pt(predictedX,predictedY);b.pressure=predictedPressure;b.tilt=predictedTilt;
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(st.color);p.setAlpha(st.tool==3?72:190);p.setStrokeWidth(Math.max(.65f,(st.width>0?st.width:4.2f)*pointWidthFactor(a,b,st.tool)));c.drawLine(a.x,a.y,b.x,b.y,p);p.setAlpha(255);
    }

    private static void drawTextEntry(Canvas c,TextEntry t,Paint p){p.setStyle(Paint.Style.FILL);p.setColor(t.color);p.setTextSize(t.size);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.LEFT);String[] ls=t.text.split("\\n",-1);float y=t.y;for(String l:ls){c.drawText(l,t.x,y,p);y+=t.size*1.25f;}}
    private void drawLasso(Canvas c){if(lassoPath.size()<2)return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(0xff3157d5);paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12,10},0));Path p=new Path();p.moveTo(lassoPath.get(0).x,lassoPath.get(0).y);for(int i=1;i<lassoPath.size();i++)p.lineTo(lassoPath.get(i).x,lassoPath.get(i).y);c.drawPath(p,paint);paint.setPathEffect(null);}
    private void drawSelection(Canvas c,RectF r){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);paint.setColor(0xff3157d5);paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10,8},0));c.drawRoundRect(r,12,12,paint);paint.setPathEffect(null);}

    @Override public boolean onTouchEvent(MotionEvent e){
        final int action=e.getActionMasked();final int ai=Math.min(e.getActionIndex(),e.getPointerCount()-1);final int type=e.getToolType(Math.max(0,ai));final boolean stylus=type==MotionEvent.TOOL_TYPE_STYLUS||type==MotionEvent.TOOL_TYPE_ERASER;
        if(action==MotionEvent.ACTION_HOVER_ENTER||action==MotionEvent.ACTION_HOVER_MOVE){stylusNear=true;return true;}if(action==MotionEvent.ACTION_HOVER_EXIT){stylusNear=false;return true;}
        if(stylus){try{if(motionPredictor!=null)motionPredictor.record(e);}catch(Throwable ignored){}return handleStylus(e,type);}if(stylusNear)return true;

        // Contextual pasted-image editing. A pasted snippet can be moved/scaled directly
        // on the sheet, then anchored so normal page gestures cannot disturb it.
        if(e.getPointerCount()==1){
            float px=pageX(e.getX()),py=pageY(e.getY());Page pg=pages.get(page);
            if(action==MotionEvent.ACTION_DOWN){
                int hit=findImageAt(px,py);
                if(hit>=0){
                    selectedImage=hit;ImageObject im=pg.images.get(hit);float hs=34f;
                    if(distance(px,py,im.x,im.y)<=hs){pushHistory();pg.images.remove(hit);selectedImage=-1;save();invalidate();return true;}
                    if(distance(px,py,im.x+im.w,im.y)<=hs){pushHistory();im.anchored=!im.anchored;save();invalidate();return true;}
                    if(!im.anchored&&distance(px,py,im.x+im.w,im.y+im.h)<=hs){pushHistory();imageResizing=true;imageMoving=false;imageTouchStartX=px;imageTouchStartY=py;imageStartX=im.x;imageStartY=im.y;imageStartW=im.w;imageStartH=im.h;return true;}
                    if(!im.anchored){pushHistory();imageMoving=true;imageResizing=false;imageTouchStartX=px;imageTouchStartY=py;imageStartX=im.x;imageStartY=im.y;return true;}
                    invalidate();return true;
                }else if(selectedImage>=0){selectedImage=-1;imageMoving=imageResizing=false;invalidate();}
            }else if(action==MotionEvent.ACTION_MOVE&&(imageMoving||imageResizing)&&hasSelectedImage()){
                ImageObject im=pg.images.get(selectedImage);
                if(imageMoving){im.x=clamp(imageStartX+(px-imageTouchStartX),0f,Math.max(0f,PAGE_W-im.w));im.y=clamp(imageStartY+(py-imageTouchStartY),0f,Math.max(0f,PAGE_H-im.h));}
                else{float dx=px-imageTouchStartX,dy=py-imageTouchStartY;float factor=Math.max(.15f,1f+Math.max(dx/Math.max(1f,imageStartW),dy/Math.max(1f,imageStartH)));float maxFactor=Math.min((PAGE_W-imageStartX)/Math.max(1f,imageStartW),(PAGE_H-imageStartY)/Math.max(1f,imageStartH));factor=Math.min(Math.max(.15f,maxFactor),factor);im.w=Math.max(90f,imageStartW*factor);im.h=Math.max(60f,imageStartH*factor);}
                invalidate();return true;
            }else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)&&(imageMoving||imageResizing)){
                imageMoving=imageResizing=false;save();invalidate();return true;
            }
        }

        if(e.getPointerCount()>=5){float span=multiSpan(e);if(!fiveFingerActive){fiveFingerActive=true;fiveFingerTriggered=false;fiveStartSpan=span;}else if(!fiveFingerTriggered&&fiveStartSpan>0&&span<fiveStartSpan*.68f){fiveFingerTriggered=true;if(listener!=null)listener.overviewRequested();}pinching=false;fingerPanning=false;threeFingerActive=false;return true;}
        if(fiveFingerActive&&e.getPointerCount()<5){fiveFingerActive=false;fiveStartSpan=0;}

        if(e.getPointerCount()==3){float cx=(e.getX(0)+e.getX(1)+e.getX(2))/3f;if(!threeFingerActive||action==MotionEvent.ACTION_POINTER_DOWN){threeFingerActive=true;threeDownX=cx;}else if(action==MotionEvent.ACTION_MOVE&&Math.abs(cx-threeDownX)>130){if(cx>threeDownX)duplicatePage(page);threeFingerActive=false;}return true;}
        if(threeFingerActive&&e.getPointerCount()<3)threeFingerActive=false;

        if(e.getPointerCount()>=2){float x0=e.getX(0),y0=e.getY(0),x1=e.getX(1),y1=e.getY(1);float fx=(x0+x1)*.5f,fy=(y0+y1)*.5f,dist=distance(x0,y0,x1,y1);if(!pinching||action==MotionEvent.ACTION_POINTER_DOWN){pinching=true;pinchStartDistance=Math.max(1,dist);pinchStartZoom=zoom;pinchAnchorPageX=pageX(fx);pinchAnchorPageY=pageY(fy);}else if(action==MotionEvent.ACTION_MOVE){zoom=clamp(pinchStartZoom*(dist/pinchStartDistance),1f,5f);float s=scale();offsetX=fx-baseX(s)-pinchAnchorPageX*s;offsetY=fy-baseY(s)-pinchAnchorPageY*s;clampPan();invalidate();}fingerPanning=false;return true;}
        if(action==MotionEvent.ACTION_POINTER_UP){pinching=false;return true;}
        if(action==MotionEvent.ACTION_DOWN){pinching=false;downX=lastX=e.getX();downY=lastY=e.getY();fingerPanning=zoom>1.02f;return true;}
        if(action==MotionEvent.ACTION_MOVE&&fingerPanning){offsetX+=e.getX()-lastX;offsetY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();clampPan();invalidate();return true;}
        if(action==MotionEvent.ACTION_UP){float dx=e.getX()-downX,dy=e.getY()-downY;if(zoom<=1.02f&&Math.abs(dx)>105&&Math.abs(dx)>Math.abs(dy)*1.3f){if(dx<0)nextPage();else previousPage();}fingerPanning=false;return true;}
        if(action==MotionEvent.ACTION_CANCEL){pinching=fingerPanning=fiveFingerActive=threeFingerActive=false;return true;}return true;
    }

    private boolean handleStylus(MotionEvent e,int type){
        int action=e.getActionMasked();boolean hardwareErase=type==MotionEvent.TOOL_TYPE_ERASER||(e.getButtonState()&MotionEvent.BUTTON_STYLUS_PRIMARY)!=0||(e.getButtonState()&MotionEvent.BUTTON_STYLUS_SECONDARY)!=0;int effective=hardwareErase?4:tool;float x=pageX(e.getX()),y=pageY(e.getY());Page pg=pages.get(page);
        if(pg.locked && effective!=5)return true;
        if(action==MotionEvent.ACTION_DOWN){saveHandler.removeCallbacks(delayedSave);predictedValid=false;stylusDownTime=System.currentTimeMillis();if(!insidePage(x,y))return true;
            if(effective==7){if(listener!=null)listener.textRequested(x,y);return true;}
            if(effective==5){
                if(selectionBounds!=null&&selectionBounds.contains(x,y)&&!selectedStrokes.isEmpty()){pushHistory();movingSelection=true;moveLastX=x;moveLastY=y;return true;}
                clearLassoSelection();lassoPath.add(new Pt(x,y));requestFrame();return true;
            }
            pushHistory();if(effective==4){erasingGesture=true;ensureInkCache(pg);lastErasePageX=x;lastErasePageY=y;eraseSegmentFast(x,y,x,y,eraserMode);}else{active=new Stroke(color,effective);active.width=widthForTool(effective);active.pts.add(stylusPt(e,x,y,-1));if(effective>=0&&effective<=2){recentActiveInk=new InkStrokeData();recentActiveInk.points.add(new InkPointData(x,y,e.getEventTime()));}}requestFrame();return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(effective==5){if(movingSelection){float dx=x-moveLastX,dy=y-moveLastY;moveSelected(dx,dy);moveLastX=x;moveLastY=y;}else if(insidePage(x,y))lassoPath.add(new Pt(x,y));requestFrame();return true;}
            if(effective==4){if(!erasingGesture){pushHistory();erasingGesture=true;ensureInkCache(pg);lastErasePageX=x;lastErasePageY=y;}if(insidePage(x,y)){float ax=Float.isNaN(lastErasePageX)?x:lastErasePageX,ay=Float.isNaN(lastErasePageY)?y:lastErasePageY;eraseSegmentFast(ax,ay,x,y,eraserMode);lastErasePageX=x;lastErasePageY=y;}}
            else if(active!=null){
                if(effective==6){if(active.pts.size()==1)active.pts.add(new Pt(x,y));else active.pts.set(1,new Pt(x,y));}
                else {for(int i=0;i<e.getHistorySize();i++){float hx=pageX(e.getHistoricalX(i)),hy=pageY(e.getHistoricalY(i));if(insidePage(hx,hy)){active.pts.add(stylusPt(e,hx,hy,i));if(recentActiveInk!=null)recentActiveInk.points.add(new InkPointData(hx,hy,e.getHistoricalEventTime(i)));}}if(insidePage(x,y)){active.pts.add(stylusPt(e,x,y,-1));if(recentActiveInk!=null)recentActiveInk.points.add(new InkPointData(x,y,e.getEventTime()));}
                    if(effective>=0&&effective<=3){try{MotionEvent pe=motionPredictor==null?null:motionPredictor.predict();if(pe!=null&&pe.getPointerCount()>0){float px=pageX(pe.getX()),py=pageY(pe.getY());if(insidePage(px,py)){predictedX=px;predictedY=py;predictedPressure=Math.max(.05f,Math.min(1.5f,pe.getPressure()));predictedTilt=Math.max(0f,pe.getAxisValue(MotionEvent.AXIS_TILT));predictedValid=true;}else predictedValid=false;pe.recycle();}else predictedValid=false;}catch(Throwable ignored){predictedValid=false;}}
                }
            }requestFrame();return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){predictedValid=false;
            if(effective==5){if(movingSelection){movingSelection=false;save();}else finishLasso();invalidate();return true;}
            if(active!=null){if(active.pts.size()>1){pg.strokes.add(active);pg.inkRevision++;appendCommittedStrokeToCache(pg,active);}active=null;}
            if(recentActiveInk!=null){if(insidePage(x,y))recentActiveInk.points.add(new InkPointData(x,y,e.getEventTime()));recentActiveInk.endedAt=android.os.SystemClock.uptimeMillis();if(recentActiveInk.points.size()>1){recentInk.add(recentActiveInk);pruneRecentInk(recentActiveInk.endedAt,10000);if(inkListener!=null)inkListener.inkChanged();}recentActiveInk=null;}
            if(effective==4)invalidateInkCache();
            erasingGesture=false;lastErasePageX=lastErasePageY=Float.NaN;save();requestFrame();return true;
        }return true;
    }

    private Pt stylusPt(MotionEvent e,float x,float y,int history){
        int pi=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));float pressure=1f,tilt=0f;
        try{pressure=history>=0?e.getHistoricalPressure(pi,history):e.getPressure(pi);}catch(Exception ignored){}
        try{tilt=history>=0?e.getHistoricalAxisValue(MotionEvent.AXIS_TILT,pi,history):e.getAxisValue(MotionEvent.AXIS_TILT,pi);}catch(Exception ignored){}
        Pt p=new Pt(x,y);p.pressure=clamp(pressure,.05f,1.5f);p.tilt=Math.max(0f,tilt);return p;
    }

    private float widthForTool(int t){if(t==3)return markerWidth;if(t==0)return Math.max(1f,penWidth*.75f);if(t==2)return penWidth*1.12f;if(t==6)return penWidth;return penWidth;}

    // Intentionally unused in V15.0.4: automatic shape snapping is disabled.
    // Straight lines remain available through the explicit ruler tool (tool 6).
    private void shapeIfApplicable(Stroke s){
        if(s.pts.size()<4)return;float len=0;for(int i=1;i<s.pts.size();i++)len+=distance(s.pts.get(i-1).x,s.pts.get(i-1).y,s.pts.get(i).x,s.pts.get(i).y);Pt a=s.pts.get(0),b=s.pts.get(s.pts.size()-1);float direct=distance(a.x,a.y,b.x,b.y);
        if(len>40&&direct/Math.max(1,len)>.94f){s.pts.clear();s.pts.add(a);s.pts.add(b);return;}
        if(len>120&&direct/len<.18f){float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;for(Pt p:s.pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}float w=maxX-minX,h=maxY-minY;if(w>35&&h>35&&w/h>.45f&&w/h<2.2f){s.pts.clear();float cx=(minX+maxX)/2f,cy=(minY+maxY)/2f,rx=w/2f,ry=h/2f;for(int i=0;i<=48;i++){double t=Math.PI*2*i/48.0;s.pts.add(new Pt(cx+(float)Math.cos(t)*rx,cy+(float)Math.sin(t)*ry));}}}
    }

    private void finishLasso(){
        if(lassoPath.size()<3){lassoPath.clear();return;}selectedStrokes.clear();Page pg=pages.get(page);for(int i=0;i<pg.strokes.size();i++){Stroke s=pg.strokes.get(i);if(s.pts.isEmpty())continue;float cx=0,cy=0;for(Pt p:s.pts){cx+=p.x;cy+=p.y;}cx/=s.pts.size();cy/=s.pts.size();if(pointInPolygon(cx,cy,lassoPath))selectedStrokes.add(i);}lassoPath.clear();recomputeSelectionBounds();
    }

    public boolean copySelection(){if(selectedStrokes.isEmpty())return false;pushHistory();Page pg=pages.get(page);ArrayList<Stroke> copy=new ArrayList<>();for(int i:selectedStrokes)if(i>=0&&i<pg.strokes.size()){Stroke s=pg.strokes.get(i).deepCopy();for(Pt p:s.pts){p.x+=28;p.y+=28;}copy.add(s);}pg.strokes.addAll(copy);pg.inkRevision++;invalidateInkCache();save();requestFrame();return true;}
    public void clearLassoSelection(){lassoPath.clear();selectedStrokes.clear();selectionBounds=null;movingSelection=false;invalidate();}
    /** Current lasso selection in normalized page coordinates, or null when nothing is selected. */
    public RectF getLassoSelectionNormalized(){if(selectionBounds==null)return null;return new RectF(clamp(selectionBounds.left/PAGE_W,0f,1f),clamp(selectionBounds.top/PAGE_H,0f,1f),clamp(selectionBounds.right/PAGE_W,0f,1f),clamp(selectionBounds.bottom/PAGE_H,0f,1f));}
    /** Focus a saved normalized region while keeping it anchored to the paper. */
    public void focusNormalizedRect(RectF n){if(n==null||getWidth()<=0||getHeight()<=0)return;float l=clamp(Math.min(n.left,n.right),0f,1f),r=clamp(Math.max(n.left,n.right),0f,1f),t=clamp(Math.min(n.top,n.bottom),0f,1f),b=clamp(Math.max(n.top,n.bottom),0f,1f);float rw=Math.max(1f,(r-l)*PAGE_W),rh=Math.max(1f,(b-t)*PAGE_H);float fs=fitScale();float targetScale=Math.min(getWidth()*.72f/rw,getHeight()*.62f/rh);zoom=clamp(targetScale/Math.max(.0001f,fs),1f,5f);float sc=scale(),cx=(l+r)*.5f*PAGE_W,cy=(t+b)*.5f*PAGE_H;offsetX=getWidth()*.5f-baseX(sc)-cx*sc;offsetY=getHeight()*.5f-baseY(sc)-cy*sc;clampPan();requestFrame();}
    private void moveSelected(float dx,float dy){Page pg=pages.get(page);for(int i:selectedStrokes)if(i>=0&&i<pg.strokes.size()){Stroke st=pg.strokes.get(i);for(Pt p:st.pts){p.x+=dx;p.y+=dy;}st.invalidateBounds();}pg.inkRevision++;invalidateInkCache();recomputeSelectionBounds();}
    private void recomputeSelectionBounds(){if(selectedStrokes.isEmpty()){selectionBounds=null;return;}float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;Page pg=pages.get(page);for(int i:selectedStrokes)if(i>=0&&i<pg.strokes.size())for(Pt p:pg.strokes.get(i).pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}selectionBounds=new RectF(minX-18,minY-18,maxX+18,maxY+18);}
    private static boolean pointInPolygon(float x,float y,ArrayList<Pt> poly){boolean in=false;for(int i=0,j=poly.size()-1;i<poly.size();j=i++){Pt a=poly.get(i),b=poly.get(j);if(((a.y>y)!=(b.y>y))&&(x<(b.x-a.x)*(y-a.y)/(b.y-a.y+0.00001f)+a.x))in=!in;}return in;}

    private int findImageAt(float x,float y){
        if(!valid(page))return -1;ArrayList<ImageObject> list=pages.get(page).images;
        for(int i=list.size()-1;i>=0;i--){ImageObject im=list.get(i);if(x>=im.x-24&&x<=im.x+im.w+24&&y>=im.y-24&&y<=im.y+im.h+24)return i;}return -1;
    }

    /**
     * Fast + gap-free eraser path. One stylus move is treated as a continuous capsule
     * instead of a handful of sampled circles. That avoids the tiny ink islands that used
     * to reappear after the cache was rebuilt, while doing less geometry work per move.
     */
    private void eraseSegmentFast(float ax,float ay,float bx,float by,int mode){
        if(!valid(page))return;Page pg=pages.get(page);float radius=24f/Math.max(.15f,scale());
        boolean cacheLive=inkCache!=null&&inkCacheCanvas!=null&&inkCachePage==page&&inkCacheRevision==pg.inkRevision;
        boolean changed=eraseAtSegmentData(pg,ax,ay,bx,by,mode,radius);
        if(!changed)return;
        pg.inkRevision++;selectedStrokes.clear();selectionBounds=null;lassoPath.clear();movingSelection=false;
        if(cacheLive){
            eraseCachePaint.setStyle(Paint.Style.STROKE);eraseCachePaint.setStrokeCap(Paint.Cap.ROUND);eraseCachePaint.setStrokeJoin(Paint.Join.ROUND);
            // The canvas itself is scaled to the high-resolution cache, so these stay page units.
            eraseCachePaint.setStrokeWidth(radius*2.35f);eraseCachePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            if(distance(ax,ay,bx,by)<.5f)inkCacheCanvas.drawCircle(bx,by,radius*1.175f,eraseCachePaint);else inkCacheCanvas.drawLine(ax,ay,bx,by,eraseCachePaint);
            eraseCachePaint.setXfermode(null);inkCacheRevision=pg.inkRevision;
        }else invalidateInkCache();
    }

    private boolean eraseAtSegmentData(Page pg,float ax,float ay,float bx,float by,int mode,float radius){
        boolean changed=false;float left=Math.min(ax,bx)-radius-40f,right=Math.max(ax,bx)+radius+40f,top=Math.min(ay,by)-radius-40f,bottom=Math.max(ay,by)+radius+40f;
        for(int i=pg.strokes.size()-1;i>=0;i--){
            Stroke st=pg.strokes.get(i);float effective=radius+Math.max(1.2f,(st.width>0?st.width:4.2f)*.92f);
            if(!st.boundsTouchesRect(left,top,right,bottom,effective)||!strokeTouchesCapsule(st,ax,ay,bx,by,effective))continue;
            if(mode==1){pg.strokes.remove(i);changed=true;continue;}
            ArrayList<Stroke> pieces=splitStrokeOutsideCapsule(st,ax,ay,bx,by,effective);
            pg.strokes.remove(i);if(!pieces.isEmpty())pg.strokes.addAll(i,pieces);changed=true;
        }
        return changed;
    }

    private static boolean strokeTouchesCapsule(Stroke st,float ax,float ay,float bx,float by,float radius){
        if(st.pts.isEmpty())return false;float r2=radius*radius;Pt prev=st.pts.get(0);
        if(pointSegmentDistanceSq(prev.x,prev.y,ax,ay,bx,by)<=r2)return true;
        for(int i=1;i<st.pts.size();i++){Pt cur=st.pts.get(i);if(segmentSegmentDistanceSq(prev.x,prev.y,cur.x,cur.y,ax,ay,bx,by)<=r2)return true;prev=cur;}return false;
    }

    private static ArrayList<Stroke> splitStrokeOutsideCapsule(Stroke src,float ax,float ay,float bx,float by,float radius){
        ArrayList<Stroke> out=new ArrayList<>();float r2=radius*radius;Stroke current=null;
        for(int i=0;i<src.pts.size();i++){
            Pt p=src.pts.get(i);boolean erase=pointSegmentDistanceSq(p.x,p.y,ax,ay,bx,by)<=r2;
            if(!erase&&i>0){Pt q=src.pts.get(i-1);if(segmentSegmentDistanceSq(q.x,q.y,p.x,p.y,ax,ay,bx,by)<=r2)erase=true;}
            if(!erase&&i+1<src.pts.size()){Pt q=src.pts.get(i+1);if(segmentSegmentDistanceSq(p.x,p.y,q.x,q.y,ax,ay,bx,by)<=r2)erase=true;}
            if(erase){if(current!=null&&current.pts.size()>=2)out.add(current);current=null;}
            else{if(current==null){current=new Stroke(src.color,src.tool);current.width=src.width;}current.pts.add(p.deepCopy());}
        }
        if(current!=null&&current.pts.size()>=2)out.add(current);return out;
    }

    private static float segmentSegmentDistanceSq(float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy){
        if(segmentsIntersect(ax,ay,bx,by,cx,cy,dx,dy))return 0f;
        return Math.min(Math.min(pointSegmentDistanceSq(ax,ay,cx,cy,dx,dy),pointSegmentDistanceSq(bx,by,cx,cy,dx,dy)),Math.min(pointSegmentDistanceSq(cx,cy,ax,ay,bx,by),pointSegmentDistanceSq(dx,dy,ax,ay,bx,by)));
    }
    private static boolean segmentsIntersect(float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy){
        float o1=cross(ax,ay,bx,by,cx,cy),o2=cross(ax,ay,bx,by,dx,dy),o3=cross(cx,cy,dx,dy,ax,ay),o4=cross(cx,cy,dx,dy,bx,by);
        if(((o1>0&&o2<0)||(o1<0&&o2>0))&&((o3>0&&o4<0)||(o3<0&&o4>0)))return true;
        final float e=.0001f;return Math.abs(o1)<=e&&onSegment(ax,ay,bx,by,cx,cy)||Math.abs(o2)<=e&&onSegment(ax,ay,bx,by,dx,dy)||Math.abs(o3)<=e&&onSegment(cx,cy,dx,dy,ax,ay)||Math.abs(o4)<=e&&onSegment(cx,cy,dx,dy,bx,by);
    }
    private static float cross(float ax,float ay,float bx,float by,float px,float py){return(bx-ax)*(py-ay)-(by-ay)*(px-ax);}
    private static boolean onSegment(float ax,float ay,float bx,float by,float px,float py){return px>=Math.min(ax,bx)-.001f&&px<=Math.max(ax,bx)+.001f&&py>=Math.min(ay,by)-.001f&&py<=Math.max(ay,by)+.001f;}

    private void clampPan(){float s=scale(),pw=PAGE_W*s,ph=PAGE_H*s,margin=64f;if(pw<=getWidth())offsetX=0;else offsetX=clamp(offsetX,getWidth()-baseX(s)-pw-margin,-baseX(s)+margin);if(ph<=getHeight())offsetY=0;else offsetY=clamp(offsetY,getHeight()-baseY(s)-ph-margin,-baseY(s)+margin);}
    private static float multiSpan(MotionEvent e){int n=Math.min(5,e.getPointerCount());float cx=0,cy=0;for(int i=0;i<n;i++){cx+=e.getX(i);cy+=e.getY(i);}cx/=n;cy/=n;float total=0;for(int i=0;i<n;i++)total+=distance(cx,cy,e.getX(i),e.getY(i));return total/n;}
    private static float distance(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}private static float sq(float v){return v*v;}private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static float pointSegmentDistanceSq(float px,float py,float ax,float ay,float bx,float by){float vx=bx-ax,vy=by-ay,wx=px-ax,wy=py-ay,vv=vx*vx+vy*vy;if(vv<=.0001f)return sq(px-ax)+sq(py-ay);float t=Math.max(0f,Math.min(1f,(wx*vx+wy*vy)/vv));float dx=px-(ax+t*vx),dy=py-(ay+t*vy);return dx*dx+dy*dy;}

    public Bitmap renderThumbnail(int index,int targetW,int targetH){targetW=Math.max(64,targetW);targetH=Math.max(90,targetH);Bitmap b=Bitmap.createBitmap(targetW,targetH,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xffe5e4df);float s=Math.min((targetW-6f)/PAGE_W,(targetH-6f)/PAGE_H);c.save();c.translate((targetW-PAGE_W*s)/2f,(targetH-PAGE_H*s)/2f);c.scale(s,s);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);p.setColor(Color.WHITE);p.setStyle(Paint.Style.FILL);c.drawRect(0,0,PAGE_W,PAGE_H,p);if(valid(index)){Page pg=pages.get(index);if(pg.backgroundPath!=null&&!pg.backgroundPath.isEmpty()){Bitmap bg=loadBitmap(pg.backgroundPath);if(bg!=null)c.drawBitmap(bg,null,new RectF(0,0,PAGE_W,PAGE_H),p);}drawPaper(c,pg.paper,p);for(ImageObject im:pg.images){Bitmap ib=loadBitmap(im.path);if(ib!=null)c.drawBitmap(ib,null,new RectF(im.x,im.y,im.x+im.w,im.y+im.h),p);}for(Stroke st:pg.strokes)drawStroke(c,st,p,false);for(TextEntry t:pg.texts)drawTextEntry(c,t,p);if(pg.bookmark){p.setColor(0xffffb300);p.setStyle(Paint.Style.FILL);Path mark=new Path();mark.moveTo(PAGE_W-90,0);mark.lineTo(PAGE_W,0);mark.lineTo(PAGE_W,120);mark.lineTo(PAGE_W-45,92);mark.lineTo(PAGE_W-90,120);mark.close();c.drawPath(mark,p);}}c.restore();return b;}

    public File exportPdf(File dest) throws Exception {
        PdfDocument doc=new PdfDocument();
        for(int i=0;i<pages.size();i++){
            PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder((int)PAGE_W,(int)PAGE_H,i+1).create();PdfDocument.Page out=doc.startPage(info);Canvas c=out.getCanvas();c.drawColor(Color.WHITE);Page pg=pages.get(i);if(pg.backgroundPath!=null&&!pg.backgroundPath.isEmpty()){Bitmap bg=loadBitmap(pg.backgroundPath);if(bg!=null)c.drawBitmap(bg,null,new RectF(0,0,PAGE_W,PAGE_H),paint);}drawPaper(c,pg.paper,paint);for(ImageObject im:pg.images){Bitmap ib=loadBitmap(im.path);if(ib!=null)c.drawBitmap(ib,null,new RectF(im.x,im.y,im.x+im.w,im.y+im.h),paint);}for(Stroke st:pg.strokes)drawStroke(c,st,paint,false);for(TextEntry t:pg.texts)drawTextEntry(c,t,paint);doc.finishPage(out);
        }
        try(FileOutputStream out=new FileOutputStream(dest)){doc.writeTo(out);}doc.close();return dest;
    }

    public void save(){
        // Never block the stylus thread with file I/O. Coalesce quick strokes into one save.
        saveHandler.removeCallbacks(delayedSave);
        saveHandler.postDelayed(delayedSave,SAVE_DEBOUNCE_MS);
    }

    public void flushSave(){
        saveHandler.removeCallbacks(delayedSave);
        final SaveState state=snapshotState();
        try{saveExecutor.submit(()->writeState(state)).get();}catch(Exception ignored){}
    }

    private void snapshotAndSaveAsync(){
        final SaveState state=snapshotState();
        saveExecutor.execute(()->writeState(state));
    }

    private SaveState snapshotState(){
        SaveState state=new SaveState();state.pageIndex=page;
        for(Page pg:pages)state.pages.add(pg.deepCopy());
        for(Page pg:pageTrash)state.trash.add(pg.deepCopy());
        return state;
    }

    private void writeState(SaveState state){
        try{
            File tmp=new File(file.getAbsolutePath()+".tmp");DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp),64*1024));d.writeInt(MAGIC);d.writeInt(state.pages.size());d.writeInt(state.pageIndex);writePages(d,state.pages);d.writeInt(state.trash.size());writePages(d,state.trash);d.flush();d.close();
            File bak=new File(file.getAbsolutePath()+".bak");if(file.exists()){if(bak.exists())bak.delete();file.renameTo(bak);}if(!tmp.renameTo(file)){copyFile(tmp,file);tmp.delete();}
            if(listener!=null)post(()->listener.saved());
        }catch(Exception ignored){}
    }

    private static void writePages(DataOutputStream d,ArrayList<Page> list)throws Exception{
        for(Page pg:list){
            d.writeBoolean(pg.bookmark);d.writeBoolean(pg.locked);d.writeInt(pg.paper);d.writeUTF(pg.title==null?"":pg.title);d.writeUTF(pg.backgroundPath==null?"":pg.backgroundPath);
            d.writeInt(pg.strokes.size());for(Stroke st:pg.strokes){d.writeInt(st.color);d.writeInt(st.tool);d.writeFloat(st.width);d.writeInt(st.pts.size());for(Pt pt:st.pts){d.writeFloat(pt.x);d.writeFloat(pt.y);d.writeFloat(pt.pressure);d.writeFloat(pt.tilt);}}
            d.writeInt(pg.texts.size());for(TextEntry t:pg.texts){d.writeFloat(t.x);d.writeFloat(t.y);d.writeFloat(t.size);d.writeInt(t.color);d.writeUTF(t.text==null?"":t.text);}
            d.writeInt(pg.images.size());for(ImageObject im:pg.images){d.writeUTF(im.path==null?"":im.path);d.writeFloat(im.x);d.writeFloat(im.y);d.writeFloat(im.w);d.writeFloat(im.h);d.writeBoolean(im.anchored);}
        }
    }

    private boolean loadV15(){
        try{if(!file.exists())return false;DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(file)));if(d.readInt()!=MAGIC){d.close();return false;}int n=d.readInt(),saved=d.readInt();readPagesV14(d,pages,n);int trash=d.readInt();readPagesV14(d,pageTrash,trash);d.close();page=Math.max(0,Math.min(saved,pages.size()-1));return!pages.isEmpty();}catch(Exception e){pages.clear();pageTrash.clear();return false;}
    }
    private static void readPagesV14(DataInputStream d,ArrayList<Page> out,int n)throws Exception{
        for(int i=0;i<n;i++){Page pg=new Page();pg.bookmark=d.readBoolean();pg.locked=d.readBoolean();pg.paper=d.readInt();pg.title=d.readUTF();pg.backgroundPath=d.readUTF();
            int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=d.readFloat();int pts=d.readInt();for(int j=0;j<pts;j++){Pt p=new Pt(d.readFloat(),d.readFloat());p.pressure=d.readFloat();p.tilt=d.readFloat();st.pts.add(p);}pg.strokes.add(st);}
            int tc=d.readInt();for(int t=0;t<tc;t++){TextEntry e=new TextEntry();e.x=d.readFloat();e.y=d.readFloat();e.size=d.readFloat();e.color=d.readInt();e.text=d.readUTF();pg.texts.add(e);}
            int ic=d.readInt();for(int q=0;q<ic;q++){ImageObject im=new ImageObject();im.path=d.readUTF();im.x=d.readFloat();im.y=d.readFloat();im.w=d.readFloat();im.h=d.readFloat();im.anchored=d.readBoolean();pg.images.add(im);}
            out.add(pg);
        }
    }

    /** One-time import of V14. Same payload as V15, but from the old filename/magic. */
    private boolean migrateDocumentV14(Context c){
        File old=new File(c.getFilesDir(),"notebook-v14-"+safe(documentId)+".bin");if(!old.exists())return false;
        try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));if(d.readInt()!=V14_MAGIC){d.close();return false;}int n=d.readInt(),saved=d.readInt();readPagesV14(d,pages,n);int trash=d.readInt();readPagesV14(d,pageTrash,trash);d.close();page=Math.max(0,Math.min(saved,pages.size()-1));flushSave();return!pages.isEmpty();}catch(Exception e){pages.clear();pageTrash.clear();return false;}
    }

    /** One-time import of the V13 notebook file. Pressure defaults to 1 for legacy points. */
    private boolean migrateDocumentV13(Context c){
        File old=new File(c.getFilesDir(),"notebook-v13-"+safe(documentId)+".bin");if(!old.exists())return false;
        try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));if(d.readInt()!=V13_MAGIC){d.close();return false;}int n=d.readInt(),saved=d.readInt();readPagesV13Legacy(d,pages,n);int trash=d.readInt();readPagesV13Legacy(d,pageTrash,trash);d.close();page=Math.max(0,Math.min(saved,pages.size()-1));flushSave();return!pages.isEmpty();}catch(Exception e){pages.clear();pageTrash.clear();return false;}
    }
    private static void readPagesV13Legacy(DataInputStream d,ArrayList<Page> out,int n)throws Exception{for(int i=0;i<n;i++){Page pg=new Page();pg.bookmark=d.readBoolean();pg.locked=d.readBoolean();pg.paper=d.readInt();pg.title=d.readUTF();pg.backgroundPath=d.readUTF();int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=d.readFloat();int pts=d.readInt();for(int j=0;j<pts;j++)st.pts.add(new Pt(d.readFloat(),d.readFloat()));pg.strokes.add(st);}int tc=d.readInt();for(int t=0;t<tc;t++){TextEntry e=new TextEntry();e.x=d.readFloat();e.y=d.readFloat();e.size=d.readFloat();e.color=d.readInt();e.text=d.readUTF();pg.texts.add(e);}int ic=d.readInt();for(int q=0;q<ic;q++){ImageObject im=new ImageObject();im.path=d.readUTF();im.x=d.readFloat();im.y=d.readFloat();im.w=d.readFloat();im.h=d.readFloat();im.anchored=d.readBoolean();pg.images.add(im);}out.add(pg);}}

    /** One-time import of the V9/V12 notebook file so existing user data survives the format upgrade. */
    private boolean migrateDocumentV9(Context c){
        File old=new File(c.getFilesDir(),"notebook-v9-"+safe(documentId)+".bin");if(!old.exists())return false;
        try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));if(d.readInt()!=V9_MAGIC){d.close();return false;}int n=d.readInt(),saved=d.readInt();readPagesV9(d,pages,n);int trash=d.readInt();readPagesV9(d,pageTrash,trash);d.close();page=Math.max(0,Math.min(saved,pages.size()-1));flushSave();return!pages.isEmpty();}catch(Exception e){pages.clear();pageTrash.clear();return false;}
    }
    private static void readPagesV9(DataInputStream d,ArrayList<Page> out,int n)throws Exception{for(int i=0;i<n;i++){Page pg=new Page();pg.bookmark=d.readBoolean();pg.locked=d.readBoolean();pg.paper=d.readInt();pg.title=d.readUTF();pg.backgroundPath=d.readUTF();int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=d.readFloat();int pts=d.readInt();for(int j=0;j<pts;j++)st.pts.add(new Pt(d.readFloat(),d.readFloat()));pg.strokes.add(st);}int tc=d.readInt();for(int t=0;t<tc;t++){TextEntry e=new TextEntry();e.x=d.readFloat();e.y=d.readFloat();e.size=d.readFloat();e.color=d.readInt();e.text=d.readUTF();pg.texts.add(e);}out.add(pg);}}

    private boolean migrateV8(Context c){File old=new File(c.getFilesDir(),"notebook-v8.bin");if(!old.exists())return false;try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));if(d.readInt()!=V8_MAGIC){d.close();return false;}int n=d.readInt();int saved=d.readInt();for(int i=0;i<n;i++){Page pg=new Page();pg.bookmark=d.readBoolean();pg.paper=d.readInt();int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=legacyWidth(st.tool);int pts=d.readInt();for(int j=0;j<pts;j++)st.pts.add(new Pt(d.readFloat(),d.readFloat()));pg.strokes.add(st);}pages.add(pg);}d.close();page=Math.max(0,Math.min(saved,pages.size()-1));flushSave();return!pages.isEmpty();}catch(Exception e){pages.clear();return false;}}
    private void migrateV7(Context c){File old=new File(c.getFilesDir(),"notebook-v7.bin");if(!old.exists())return;try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));if(d.readInt()!=0x53423731){d.close();return;}int n=d.readInt();for(int i=0;i<n;i++){Page pg=new Page();pg.bookmark=d.readBoolean();pg.paper=d.readInt();int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=legacyWidth(st.tool);int pts=d.readInt();for(int j=0;j<pts;j++)st.pts.add(new Pt(d.readFloat(),d.readFloat()));pg.strokes.add(st);}pages.add(pg);}d.close();flushSave();}catch(Exception ignored){pages.clear();}}
    private void migrateV3(Context c){File old=new File(c.getFilesDir(),"notebook-v3.bin");if(!old.exists())return;try{DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(old)));Page pg=new Page();int count=d.readInt();for(int k=0;k<count;k++){Stroke st=new Stroke(d.readInt(),d.readInt());st.width=legacyWidth(st.tool);int pts=d.readInt();for(int j=0;j<pts;j++)st.pts.add(new Pt(d.readFloat(),d.readFloat()));pg.strokes.add(st);}d.close();pages.add(pg);flushSave();}catch(Exception ignored){pages.clear();}}
    private static float legacyWidth(int tool){return tool==0?2f:tool==2?3.2f:tool==3?18f:4.2f;}
    private static void copyFile(File a,File b)throws Exception{try(FileInputStream in=new FileInputStream(a);FileOutputStream out=new FileOutputStream(b)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}}

    static class SaveState {int pageIndex;final ArrayList<Page> pages=new ArrayList<>();final ArrayList<Page> trash=new ArrayList<>();}

    static class Page {
        final ArrayList<Stroke> strokes=new ArrayList<>();final ArrayList<TextEntry> texts=new ArrayList<>();final ArrayList<ImageObject> images=new ArrayList<>();boolean bookmark;boolean locked;int paper;String title="";String backgroundPath="";int inkRevision=0;
        boolean isEmpty(){return strokes.isEmpty()&&texts.isEmpty()&&images.isEmpty()&&(backgroundPath==null||backgroundPath.isEmpty());}
        Page deepCopy(){Page p=new Page();p.bookmark=bookmark;p.locked=locked;p.paper=paper;p.title=title;p.backgroundPath=backgroundPath;p.inkRevision=inkRevision;for(Stroke st:strokes)p.strokes.add(st.deepCopy());for(TextEntry t:texts)p.texts.add(t.deepCopy());for(ImageObject im:images)p.images.add(im.deepCopy());return p;}
    }
    static class ImageObject {String path="";float x,y,w,h;boolean anchored;ImageObject deepCopy(){ImageObject i=new ImageObject();i.path=path;i.x=x;i.y=y;i.w=w;i.h=h;i.anchored=anchored;return i;}}
    static class Pt {float x,y,pressure=1f,tilt=0f;Pt(float x,float y){this.x=x;this.y=y;}Pt deepCopy(){Pt q=new Pt(x,y);q.pressure=pressure;q.tilt=tilt;return q;}}
    static class Stroke {
        int color,tool;float width;final ArrayList<Pt> pts=new ArrayList<>();
        private boolean boundsDirty=true;private float minX,minY,maxX,maxY;
        Stroke(int color,int tool){this.color=color;this.tool=tool;}
        void invalidateBounds(){boundsDirty=true;}
        private void ensureBounds(){if(!boundsDirty)return;if(pts.isEmpty()){minX=minY=Float.MAX_VALUE;maxX=maxY=-Float.MAX_VALUE;boundsDirty=false;return;}minX=minY=Float.MAX_VALUE;maxX=maxY=-Float.MAX_VALUE;for(Pt p:pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}boundsDirty=false;}
        boolean boundsTouches(float x,float y,float r){ensureBounds();return x+r>=minX&&x-r<=maxX&&y+r>=minY&&y-r<=maxY;}
        boolean boundsTouchesRect(float left,float top,float right,float bottom,float extra){ensureBounds();return right+extra>=minX&&left-extra<=maxX&&bottom+extra>=minY&&top-extra<=maxY;}
        Stroke deepCopy(){Stroke s=new Stroke(color,tool);s.width=width;for(Pt p:pts)s.pts.add(p.deepCopy());return s;}
    }
    static class TextEntry {float x,y,size;int color;String text="";TextEntry deepCopy(){TextEntry t=new TextEntry();t.x=x;t.y=y;t.size=size;t.color=color;t.text=text;return t;}}
    static class PageSnapshot {int pageIndex;Page page;PageSnapshot(int i,Page p){pageIndex=i;page=p;}}
}
