package de.millione.schulranzen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent, offline-first annotations for imported PDF books. Coordinates are normalized 0..1. */
public final class BookAnnotationStore {
    public static final int TOOL_PEN = 1;
    public static final int TOOL_MARKER = 2;

    public static final class Pt {
        public float x, y, pressure=1f, tilt=0f;
        public Pt() {}
        public Pt(float x,float y){this.x=x;this.y=y;}
        public Pt(float x,float y,float pressure,float tilt){this.x=x;this.y=y;this.pressure=pressure;this.tilt=tilt;}
    }
    public static final class Stroke {
        public String id=UUID.randomUUID().toString();
        public int tool=TOOL_PEN;
        public int color=Color.BLACK;
        /** Width relative to rendered PDF bitmap width. */
        public float width=.004f;
        public final ArrayList<Pt> pts=new ArrayList<>();
        private boolean boundsDirty=true;private float minX,minY,maxX,maxY;
        public void invalidateBounds(){boundsDirty=true;}
        private void ensureBounds(){if(!boundsDirty)return;if(pts.isEmpty()){minX=minY=Float.MAX_VALUE;maxX=maxY=-Float.MAX_VALUE;boundsDirty=false;return;}minX=minY=Float.MAX_VALUE;maxX=maxY=-Float.MAX_VALUE;for(Pt p:pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}boundsDirty=false;}
        public boolean boundsTouches(float x,float y,float r){ensureBounds();return x+r>=minX&&x-r<=maxX&&y+r>=minY&&y-r<=maxY;}
    }
    public static final class Note {
        public String id=UUID.randomUUID().toString();
        public float x,y;
        public String text="";
        public long created=System.currentTimeMillis();
    }
    public static final class Favorite {
        public String id=UUID.randomUUID().toString();
        public int page;
        public RectF rect=new RectF();
        public String label="Markierung";
        public long created=System.currentTimeMillis();
    }
    public static final class PageData {
        public boolean bookmarked=false;
        public final ArrayList<Stroke> strokes=new ArrayList<>();
        public final ArrayList<Note> notes=new ArrayList<>();
    }

    private final File file;
    private final HashMap<Integer,PageData> pages=new HashMap<>();
    private final ArrayList<Favorite> favorites=new ArrayList<>();
    private final ArrayDeque<String> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    private static final int HISTORY_LIMIT=30;
    private final Handler saveHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService saveExecutor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"book-annotation-save");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final Runnable delayedSave=this::snapshotAndSaveAsync;

    public BookAnnotationStore(Context c,String bookId){
        file=new File(c.getFilesDir(),"book-annotations-v12-"+safe(bookId)+".json");
        load();
    }
    private static String safe(String s){return (s==null?"book":s).replaceAll("[^a-zA-Z0-9._-]","_");}
    public synchronized PageData page(int index){PageData p=pages.get(index);if(p==null){p=new PageData();pages.put(index,p);}return p;}
    public synchronized boolean isBookmarked(int index){PageData p=pages.get(index);return p!=null&&p.bookmarked;}
    public synchronized ArrayList<Note> notesForPage(int index){PageData p=pages.get(index);return p==null?new ArrayList<>():new ArrayList<>(p.notes);}
    public synchronized void toggleBookmark(int index){checkpoint();PageData p=page(index);p.bookmarked=!p.bookmarked;save();}
    public synchronized ArrayList<Favorite> favorites(){return new ArrayList<>(favorites);}
    public synchronized Favorite addFavorite(int page,RectF r,String label){checkpoint();Favorite f=new Favorite();f.page=page;f.rect=new RectF(clamp(r.left),clamp(r.top),clamp(r.right),clamp(r.bottom));f.label=label==null||label.trim().isEmpty()?"Markierung":label.trim();favorites.add(f);save();return f;}
    public synchronized void removeFavorite(String id){checkpoint();favorites.removeIf(f->f.id.equals(id));save();}
    public synchronized void addNote(int page,float x,float y,String text){if(text==null||text.trim().isEmpty())return;checkpoint();Note n=new Note();n.x=clamp(x);n.y=clamp(y);n.text=text.trim();page(page).notes.add(n);save();}
    public synchronized boolean removeNote(int page,String id){checkpoint();PageData pd=page(page);boolean changed=pd.notes.removeIf(n->n.id.equals(id));if(changed)save();return changed;}
    public synchronized boolean eraseNoteNear(int page,float x,float y,float radius){PageData pd=page(page);float rr=radius*radius;for(int i=pd.notes.size()-1;i>=0;i--){Note n=pd.notes.get(i);float dx=n.x-x,dy=n.y-y;if(dx*dx+dy*dy<=rr){pd.notes.remove(i);return true;}}return false;}
    public synchronized void addStroke(int page,Stroke stroke){if(stroke==null||stroke.pts.size()<1)return;page(page).strokes.add(stroke);save();}
    public synchronized boolean eraseStrokeNear(int page,float x,float y,float radius){PageData pd=page(page);float rr=radius*radius;for(int s=pd.strokes.size()-1;s>=0;s--){Stroke st=pd.strokes.get(s);if(!st.boundsTouches(x,y,radius))continue;for(Pt p:st.pts){float dx=p.x-x,dy=p.y-y;if(dx*dx+dy*dy<=rr){pd.strokes.remove(s);return true;}}}return false;}
    public synchronized void commit(){save();}
    /** Keeps annotations attached to PDF content when the displayed page is rotated clockwise. */
    public synchronized void rotatePageClockwise(int index){checkpoint();
        PageData pd=pages.get(index);if(pd!=null){for(Stroke s:pd.strokes){for(Pt p:s.pts){float ox=p.x,oy=p.y;p.x=clamp(1f-oy);p.y=clamp(ox);}s.invalidateBounds();}for(Note n:pd.notes){float ox=n.x,oy=n.y;n.x=clamp(1f-oy);n.y=clamp(ox);}}
        for(Favorite f:favorites)if(f.page==index){float l=f.rect.left,t=f.rect.top,r=f.rect.right,b=f.rect.bottom;float nl=1f-b,nt=l,nr=1f-t,nb=r;f.rect.set(clamp(nl),clamp(nt),clamp(nr),clamp(nb));}
        save();
    }

    public synchronized void checkpoint(){
        try{undo.push(toJson().toString());while(undo.size()>HISTORY_LIMIT)undo.removeLast();redo.clear();}catch(Exception ignored){}
    }
    public synchronized boolean canUndo(){return !undo.isEmpty();}
    public synchronized boolean canRedo(){return !redo.isEmpty();}
    public synchronized boolean undo(){if(undo.isEmpty())return false;try{redo.push(toJson().toString());restoreJson(new JSONObject(undo.pop()));save();return true;}catch(Exception e){return false;}}
    public synchronized boolean redo(){if(redo.isEmpty())return false;try{undo.push(toJson().toString());restoreJson(new JSONObject(redo.pop()));save();return true;}catch(Exception e){return false;}}

    public synchronized void save(){saveHandler.removeCallbacks(delayedSave);saveHandler.postDelayed(delayedSave,500);}
    public void deferPendingSave(){saveHandler.removeCallbacks(delayedSave);}
    public void flushSave(){saveHandler.removeCallbacks(delayedSave);try{final String json;synchronized(this){json=toJson().toString();}saveExecutor.submit(()->writeJson(json)).get();}catch(Exception ignored){}}
    private void snapshotAndSaveAsync(){try{final String json;synchronized(this){json=toJson().toString();}saveExecutor.execute(()->writeJson(json));}catch(Exception ignored){}}
    private void writeJson(String json){try{File tmp=new File(file.getAbsolutePath()+".tmp");try(BufferedWriter w=new BufferedWriter(new FileWriter(tmp,false))){w.write(json);}if(file.exists())file.delete();if(!tmp.renameTo(file)){try(BufferedWriter w=new BufferedWriter(new FileWriter(file,false))){w.write(json);}tmp.delete();}}catch(Exception ignored){}}

    private JSONObject toJson() throws Exception{
        JSONObject root=new JSONObject();JSONArray pa=new JSONArray();
        for(Map.Entry<Integer,PageData> e:pages.entrySet()){
            JSONObject po=new JSONObject();po.put("page",e.getKey());po.put("bookmark",e.getValue().bookmarked);
            JSONArray sa=new JSONArray();for(Stroke s:e.getValue().strokes){JSONObject so=new JSONObject();so.put("id",s.id);so.put("tool",s.tool);so.put("color",s.color);so.put("width",s.width);JSONArray pts=new JSONArray();for(Pt p:s.pts){JSONArray q=new JSONArray();q.put(p.x);q.put(p.y);q.put(p.pressure);q.put(p.tilt);pts.put(q);}so.put("pts",pts);sa.put(so);}po.put("strokes",sa);
            JSONArray na=new JSONArray();for(Note n:e.getValue().notes){JSONObject no=new JSONObject();no.put("id",n.id);no.put("x",n.x);no.put("y",n.y);no.put("text",n.text);no.put("created",n.created);na.put(no);}po.put("notes",na);pa.put(po);
        }root.put("pages",pa);
        JSONArray fa=new JSONArray();for(Favorite f:favorites){JSONObject o=new JSONObject();o.put("id",f.id);o.put("page",f.page);o.put("l",f.rect.left);o.put("t",f.rect.top);o.put("r",f.rect.right);o.put("b",f.rect.bottom);o.put("label",f.label);o.put("created",f.created);fa.put(o);}root.put("favorites",fa);return root;
    }

    private synchronized void load(){pages.clear();favorites.clear();if(!file.exists())return;try{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new FileReader(file))){String l;while((l=r.readLine())!=null)b.append(l);}restoreJson(new JSONObject(b.toString()));}catch(Exception ignored){}}
    private void restoreJson(JSONObject root)throws Exception{
        pages.clear();favorites.clear();JSONArray pa=root.optJSONArray("pages");if(pa!=null)for(int i=0;i<pa.length();i++){JSONObject po=pa.getJSONObject(i);int page=po.optInt("page",0);PageData pd=new PageData();pd.bookmarked=po.optBoolean("bookmark",false);JSONArray sa=po.optJSONArray("strokes");if(sa!=null)for(int j=0;j<sa.length();j++){JSONObject so=sa.getJSONObject(j);Stroke s=new Stroke();s.id=so.optString("id",s.id);s.tool=so.optInt("tool",TOOL_PEN);s.color=so.optInt("color",Color.BLACK);s.width=(float)so.optDouble("width",.004);JSONArray pts=so.optJSONArray("pts");if(pts!=null)for(int k=0;k<pts.length();k++){JSONArray q=pts.getJSONArray(k);Pt p=new Pt((float)q.optDouble(0),(float)q.optDouble(1));p.pressure=(float)q.optDouble(2,1.0);p.tilt=(float)q.optDouble(3,0.0);s.pts.add(p);}pd.strokes.add(s);}JSONArray na=po.optJSONArray("notes");if(na!=null)for(int j=0;j<na.length();j++){JSONObject no=na.getJSONObject(j);Note n=new Note();n.id=no.optString("id",n.id);n.x=(float)no.optDouble("x");n.y=(float)no.optDouble("y");n.text=no.optString("text","");n.created=no.optLong("created",n.created);pd.notes.add(n);}pages.put(page,pd);}
        JSONArray fa=root.optJSONArray("favorites");if(fa!=null)for(int i=0;i<fa.length();i++){JSONObject o=fa.getJSONObject(i);Favorite f=new Favorite();f.id=o.optString("id",f.id);f.page=o.optInt("page",0);f.rect=new RectF((float)o.optDouble("l"),(float)o.optDouble("t"),(float)o.optDouble("r"),(float)o.optDouble("b"));f.label=o.optString("label","Markierung");f.created=o.optLong("created",f.created);favorites.add(f);}
    }
    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
}
