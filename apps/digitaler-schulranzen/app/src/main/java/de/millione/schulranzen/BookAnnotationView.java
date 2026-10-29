package de.millione.schulranzen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Stylus-only annotation layer placed above PdfPane. Fingers fall through to the PDF reader. */
public final class BookAnnotationView extends View {
    public static final int TOOL_PEN=1, TOOL_MARKER=2, TOOL_ERASER=3, TOOL_NOTE=4, TOOL_CLIP=5;
    public interface Listener {
        void noteRequested(float nx,float ny);
        void regionSelected(RectF normalized);
        void changed();
    }

    private final PdfPane pdf;
    private final BookAnnotationStore store;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private final Path path=new Path();
    private Listener listener;
    private BookAnnotationStore.Stroke active;
    private RectF selection;
    private boolean eraseChanged=false;
    private float lastEraseX=Float.NaN,lastEraseY=Float.NaN;
    private float clipStartX,clipStartY;
    private int tool=TOOL_PEN;
    private int color=Color.BLACK;
    private float penWidth=.0040f;
    private float markerWidth=.020f;
    private int pressureMode=2;
    private boolean tiltEnabled=true;

    public BookAnnotationView(Context c,PdfPane pdf,BookAnnotationStore store){
        super(c);this.pdf=pdf;this.store=store;setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false);setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    public void setListener(Listener l){listener=l;}
    public int getTool(){return tool;}
    public void setTool(int t){tool=Math.max(TOOL_PEN,Math.min(TOOL_CLIP,t));selection=null;requestFrame();}
    public int getColor(){return color;}
    public void setColor(int c){color=c;requestFrame();}
    public float getPenWidth(){return penWidth;}
    public float getMarkerWidth(){return markerWidth;}
    public void setPenWidth(float w){penWidth=Math.max(.0012f,Math.min(.020f,w));}
    public void setMarkerWidth(float w){markerWidth=Math.max(.008f,Math.min(.060f,w));}
    public void setPressureMode(int mode){pressureMode=Math.max(0,Math.min(3,mode));}
    public void setTiltEnabled(boolean enabled){tiltEnabled=enabled;}
    public RectF getSelection(){return selection==null?null:new RectF(selection);}
    public void clearSelection(){selection=null;requestFrame();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);int bw=pdf.getBitmapWidth(),bh=pdf.getBitmapHeight();if(bw<=0||bh<=0)return;
        Matrix m=pdf.getImageMatrixCopy();c.save();c.concat(m);
        BookAnnotationStore.PageData pd=store.page(pdf.getCurrentPage());
        for(BookAnnotationStore.Stroke s:pd.strokes)drawStroke(c,s,bw,bh);
        for(BookAnnotationStore.Note n:pd.notes)drawNote(c,n,bw,bh);
        for(BookAnnotationStore.Favorite f:store.favorites())if(f.page==pdf.getCurrentPage())drawFavorite(c,f,bw,bh);
        if(active!=null)drawStroke(c,active,bw,bh);
        if(selection!=null){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(2f,bw*.002f));paint.setColor(0xff2f6fed);paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f,8f},0));RectF r=scaled(selection,bw,bh);c.drawRoundRect(r,10f,10f,paint);paint.setPathEffect(null);}
        c.restore();
    }
    private void drawStroke(Canvas c,BookAnnotationStore.Stroke s,int bw,int bh){if(s.pts.isEmpty())return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);if(s.tool==BookAnnotationStore.TOOL_MARKER){paint.setColor((s.color&0x00ffffff)|0x66000000);}else paint.setColor((s.color&0x00ffffff)|0xff000000);if(s.pts.size()==1){BookAnnotationStore.Pt p0=s.pts.get(0);paint.setStrokeWidth(Math.max(1.2f,s.width*bw*pointWidthFactor(p0,p0,s.tool)));c.drawCircle(p0.x*bw,p0.y*bh,paint.getStrokeWidth()*.5f,paint);return;}for(int i=1;i<s.pts.size();i++){BookAnnotationStore.Pt a=s.pts.get(i-1),b=s.pts.get(i);paint.setStrokeWidth(Math.max(1.2f,s.width*bw*pointWidthFactor(a,b,s.tool)));c.drawLine(a.x*bw,a.y*bh,b.x*bw,b.y*bh,paint);}}
    private float pointWidthFactor(BookAnnotationStore.Pt a,BookAnnotationStore.Pt b,int tool){float pressure=(a.pressure+b.pressure)*.5f,f;if(pressureMode==0)f=1f;else if(pressureMode==1)f=.78f+.42f*pressure;else if(pressureMode==3)f=.34f+1.34f*(float)Math.pow(pressure,1.22);else f=.52f+.88f*(float)Math.sqrt(Math.max(0f,pressure));if(tool==BookAnnotationStore.TOOL_MARKER)f=.84f+(f-1f)*.32f;if(tiltEnabled&&tool==BookAnnotationStore.TOOL_MARKER){float tilt=Math.max(a.tilt,b.tilt);f*=1f+Math.min(.35f,Math.max(0f,tilt)/(float)(Math.PI/2)*.35f);}return Math.max(.35f,Math.min(1.8f,f));}
    private void drawNote(Canvas c,BookAnnotationStore.Note n,int bw,int bh){float x=n.x*bw,y=n.y*bh,size=Math.max(18f,bw*.018f);paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffd85a);c.drawRoundRect(x,y,x+size*1.45f,y+size*1.2f,size*.18f,size*.18f,paint);paint.setColor(0xff5c4910);paint.setTextSize(size*.72f);paint.setFakeBoldText(true);c.drawText("N",x+size*.45f,y+size*.85f,paint);paint.setFakeBoldText(false);}
    private void drawFavorite(Canvas c,BookAnnotationStore.Favorite f,int bw,int bh){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(2f,bw*.0025f));paint.setColor(0xffffb000);paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f,9f},0));c.drawRoundRect(scaled(f.rect,bw,bh),12f,12f,paint);paint.setPathEffect(null);}
    private static RectF scaled(RectF n,int bw,int bh){return new RectF(n.left*bw,n.top*bh,n.right*bw,n.bottom*bh);}


    /** Returns the selected PDF crop with current handwritten annotations burned into the bitmap copy. */
    public android.graphics.Bitmap cropWithAnnotations(RectF region,int maxLongEdge){
        android.graphics.Bitmap cut=pdf.cropCurrentPage(region,maxLongEdge);if(cut==null||region==null)return cut;
        Canvas c=new Canvas(cut);float rw=Math.max(.0001f,region.width()),rh=Math.max(.0001f,region.height());BookAnnotationStore.PageData pd=store.page(pdf.getCurrentPage());
        for(BookAnnotationStore.Stroke s:pd.strokes){if(s.pts.isEmpty())continue;paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(s.tool==BookAnnotationStore.TOOL_MARKER?((s.color&0x00ffffff)|0x66000000):((s.color&0x00ffffff)|0xff000000));if(s.pts.size()==1){BookAnnotationStore.Pt q=s.pts.get(0);float x=(q.x-region.left)/rw*cut.getWidth(),y=(q.y-region.top)/rh*cut.getHeight();paint.setStrokeWidth(Math.max(1.2f,s.width/rw*cut.getWidth()*pointWidthFactor(q,q,s.tool)));c.drawCircle(x,y,paint.getStrokeWidth()*.5f,paint);}else for(int i=1;i<s.pts.size();i++){BookAnnotationStore.Pt a=s.pts.get(i-1),b=s.pts.get(i);float ax=(a.x-region.left)/rw*cut.getWidth(),ay=(a.y-region.top)/rh*cut.getHeight(),bx=(b.x-region.left)/rw*cut.getWidth(),by=(b.y-region.top)/rh*cut.getHeight();paint.setStrokeWidth(Math.max(1.2f,s.width/rw*cut.getWidth()*pointWidthFactor(a,b,s.tool)));c.drawLine(ax,ay,bx,by,paint);}}
        for(BookAnnotationStore.Note n:pd.notes)if(region.contains(n.x,n.y)){float x=(n.x-region.left)/rw*cut.getWidth(),y=(n.y-region.top)/rh*cut.getHeight(),sz=Math.max(16f,cut.getWidth()*.025f);paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffd85a);c.drawRoundRect(x,y,x+sz*1.4f,y+sz*1.15f,sz*.18f,sz*.18f,paint);paint.setColor(0xff5c4910);paint.setTextSize(sz*.7f);paint.setFakeBoldText(true);c.drawText("N",x+sz*.4f,y+sz*.8f,paint);paint.setFakeBoldText(false);}
        return cut;
    }


    /** Bounded resampling keeps the book eraser responsive on high-frequency S-Pen input. */
    private boolean eraseAlong(float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay,dist=(float)Math.hypot(dx,dy);float step=.012f;int n=Math.max(1,Math.min(8,(int)Math.ceil(dist/step)));boolean changed=false;int pg=pdf.getCurrentPage();
        for(int i=0;i<=n;i++){float t=i/(float)n,x=ax+dx*t,y=ay+dy*t;changed|=store.eraseNoteNear(pg,x,y,.035f);changed|=store.eraseStrokeNear(pg,x,y,.018f);}
        return changed;
    }

    private BookAnnotationStore.Pt stylusPt(MotionEvent e,float x,float y,int history){int pi=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));float pressure=1f,tilt=0f;try{pressure=history>=0?e.getHistoricalPressure(pi,history):e.getPressure(pi);}catch(Exception ignored){}try{tilt=history>=0?e.getHistoricalAxisValue(MotionEvent.AXIS_TILT,pi,history):e.getAxisValue(MotionEvent.AXIS_TILT,pi);}catch(Exception ignored){}return new BookAnnotationStore.Pt(x,y,Math.max(.05f,Math.min(1.5f,pressure)),Math.max(0f,tilt));}

    private void requestFrame(){postInvalidateOnAnimation();}

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getPointerCount()<1)return false;int ai=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));int tt=e.getToolType(ai);boolean stylus=tt==MotionEvent.TOOL_TYPE_STYLUS||tt==MotionEvent.TOOL_TYPE_ERASER;if(!stylus)return false;
        float[] n=pdf.viewToNormalized(e.getX(),e.getY());if(n==null)return true;float x=n[0],y=n[1];int action=e.getActionMasked();boolean hwErase=tt==MotionEvent.TOOL_TYPE_ERASER||(e.getButtonState()&MotionEvent.BUTTON_STYLUS_PRIMARY)!=0||(e.getButtonState()&MotionEvent.BUTTON_STYLUS_SECONDARY)!=0;int effective=hwErase?TOOL_ERASER:tool;
        if(action==MotionEvent.ACTION_DOWN){
            store.deferPendingSave();
            if(effective==TOOL_NOTE){if(listener!=null)listener.noteRequested(x,y);return true;}
            if(effective==TOOL_CLIP){clipStartX=x;clipStartY=y;selection=new RectF(x,y,x,y);requestFrame();return true;}
            if(effective==TOOL_ERASER){store.checkpoint();lastEraseX=x;lastEraseY=y;eraseChanged=eraseAlong(x,y,x,y);requestFrame();return true;}
            store.checkpoint();active=new BookAnnotationStore.Stroke();active.tool=effective==TOOL_MARKER?BookAnnotationStore.TOOL_MARKER:BookAnnotationStore.TOOL_PEN;active.color=color;active.width=effective==TOOL_MARKER?markerWidth:penWidth;active.pts.add(stylusPt(e,x,y,-1));requestFrame();return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(effective==TOOL_CLIP){selection=new RectF(Math.min(clipStartX,x),Math.min(clipStartY,y),Math.max(clipStartX,x),Math.max(clipStartY,y));requestFrame();return true;}
            if(effective==TOOL_ERASER){float ax=Float.isNaN(lastEraseX)?x:lastEraseX,ay=Float.isNaN(lastEraseY)?y:lastEraseY;eraseChanged|=eraseAlong(ax,ay,x,y);lastEraseX=x;lastEraseY=y;requestFrame();return true;}
            if(active!=null){for(int i=0;i<e.getHistorySize();i++){float[] q=pdf.viewToNormalized(e.getHistoricalX(i),e.getHistoricalY(i));if(q!=null)active.pts.add(stylusPt(e,q[0],q[1],i));}active.pts.add(stylusPt(e,x,y,-1));requestFrame();return true;}
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            if(effective==TOOL_CLIP){if(selection!=null&&selection.width()>.02f&&selection.height()>.02f&&listener!=null)listener.regionSelected(new RectF(selection));requestFrame();return true;}
            if(effective==TOOL_ERASER){lastEraseX=lastEraseY=Float.NaN;if(eraseChanged){store.commit();eraseChanged=false;if(listener!=null)listener.changed();}requestFrame();return true;}
            if(active!=null){store.addStroke(pdf.getCurrentPage(),active);active=null;if(listener!=null)listener.changed();requestFrame();}return true;
        }
        return true;
    }
}
