package de.millione.schulranzen;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.ProgressDialog;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.TextClock;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_BOOK_PDF=20, REQ_SHEET_IMAGE=21, REQ_SHEET_CAMERA=22, REQ_SHEET_PDF=23, REQ_EXPORT=24;
    private static final int BLUE=0xff3157d5, INK=0xff272522, PAPER_BG=0xfff4f1ea, SURFACE=0xffffffff, MUTED=0xff746f67;

    private FrameLayout root, overviewOverlay;
    private FrameLayout calculatorWindow;
    private TextView hoverLabel, mathHintBubble;
    private LinearLayout activeToolDock;
    private FrameLayout transientToolPanel;
    private TextView dockWidthLabel,dockColorButton,dockUndo,dockRedo,dockPen,dockMarker,dockEraser,dockMore;
    private ThicknessSlider dockThickness;
    private LinearLayout dockColors;
    private final ArrayList<Bitmap> overviewBitmaps=new ArrayList<>();
    private int overviewGeneration=0;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Runnable pendingMathRecognition;
    private long lastMathInkTime=0;
    private long lastCircledMathToken=-1L;
    private int mathRecognitionGeneration=0;
    private MathInkRecognizer mathRecognizer;
    private double calculatorAns=0;
    private String lastMathResult="";
    private NotebookView note;
    private PdfPane pdf;
    private BookAnnotationStore bookAnnotations;
    private BookAnnotationView bookInk;
    private BookBubble bookBubble;
    private LibraryStore library;
    private StudyStore study;
    private BackupManager backup;
    private SharedPreferences prefs;
    private TextView pageInfo;
    private CornerBubble bubble;
    private int previousTool=1;
    private final ArrayList<View> radialViews=new ArrayList<>();
    private LibraryStore.Item currentItem;
    private boolean dirty=false;
    private String screen="library";

    private String typeFilter="all",subjectFilter="all",searchQuery="";
    private String pendingTitle="",pendingSubject="Allgemein";
    private int pendingColor=BLUE;
    private Uri cameraOutput;
    private File pendingExport;
    private String pendingSplitBookUri;

    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("bag",0);applySystemBars();library=new LibraryStore(this);library.relinkLocalBooks();study=new StudyStore(this);backup=new BackupManager(this);mathRecognizer=new MathInkRecognizer(this);showLibrary();}

    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    private boolean darkMode(){if(prefs==null)return false;String m=prefs.getString("theme_mode","auto");if("dark".equals(m))return true;if("light".equals(m))return false;return (getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    private int uiBg(){return darkMode()?0xff17191d:0xfff4f4f2;}
    private int uiSurface(){return darkMode()?0xff23262c:0xfffafafa;}
    private int uiRaised(){return darkMode()?0xff2b2f36:0xffffffff;}
    private int uiInk(){return darkMode()?0xfff1f2f4:0xff272522;}
    private int uiMuted(){return darkMode()?0xffa8abb1:0xff746f67;}
    private int remapTextColor(int c){if(!darkMode())return c;if(c==INK||c==0xff5b574f||c==0xff55514b||c==0xff47433e||c==0xff565149)return uiInk();if(c==MUTED||c==0xff69645c||c==0xff6f6b64)return uiMuted();return c;}
    private int remapSurfaceColor(int c){if(!darkMode())return c;int a=(c>>>24)&255,r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;if(r>220&&g>220&&b>220)return (a<<24)|(uiSurface()&0x00ffffff);return c;}
    private GradientDrawable rounded(int color,float radius){GradientDrawable g=new GradientDrawable();g.setColor(remapSurfaceColor(color));g.setCornerRadius(radius);return g;}
    private GradientDrawable roundedStroke(int fill,float radius,int stroke){GradientDrawable g=rounded(fill,radius);g.setStroke(dp(1),darkMode()?0x334f5661:stroke);return g;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(remapTextColor(color));v.setGravity(Gravity.CENTER);v.setIncludeFontPadding(false);v.getPaint().setAntiAlias(true);v.getPaint().setSubpixelText(true);v.getPaint().setDither(true);v.getPaint().setHinting(Paint.HINTING_ON);v.setLayerType(View.LAYER_TYPE_HARDWARE,null);return v;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void applySystemBars(){int bg=uiBg();getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);int flags=0;if(!darkMode()&&Build.VERSION.SDK_INT>=23)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;if(!darkMode()&&Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(flags);}

    // ---------------- LIBRARY ----------------
    private void showLibrary(){
        screen="library";
        closePdf();cancelTransientUi();note=null;currentItem=null;overviewOverlay=null;radialViews.clear();
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();
        int accent=library.subjectColor(subjectFilter.equals("all")?"Allgemein":subjectFilter);
        addAmbientAccent(root,accent);

        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(28),dp(12),dp(28),dp(12));root.addView(page,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.setGravity(Gravity.CENTER_VERTICAL);
        TextView over=text("DIGITALER SCHULRANZEN",10,uiMuted());over.setGravity(Gravity.LEFT);over.setLetterSpacing(.16f);titleBox.addView(over,new LinearLayout.LayoutParams(-1,dp(22)));
        TextView title=text("Mein Schulranzen",31,uiInk());title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);titleBox.addView(title,new LinearLayout.LayoutParams(-1,dp(42)));
        header.addView(titleBox,new LinearLayout.LayoutParams(0,dp(66),1));
        TextClock clock=plainClock(18);clock.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);header.addView(clock,new LinearLayout.LayoutParams(dp(90),dp(54)));
        header.addView(roundHeaderButton("⌕","Suchen",v->searchDialog()),new LinearLayout.LayoutParams(dp(50),dp(50)));
        header.addView(roundHeaderButton("⚙","Einstellungen",v->settings()),new LinearLayout.LayoutParams(dp(50),dp(50)));
        page.addView(header);

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        actions.addView(libraryAction("▦", "Stundenplan", accent, v->showTimetables()),new LinearLayout.LayoutParams(dp(155),dp(46)));
        LinearLayout.LayoutParams learnLp=new LinearLayout.LayoutParams(dp(125),dp(46));learnLp.leftMargin=dp(10);actions.addView(libraryAction("◎", "Lernen", accent, v->showStudyHome()),learnLp);
        LinearLayout.LayoutParams shkLp=new LinearLayout.LayoutParams(dp(125),dp(46));shkLp.leftMargin=dp(10);actions.addView(libraryAction("⌁", "SHK Tools", accent, v->showShkHome()),shkLp);
        TextClock day=new TextClock(this);day.setFormat24Hour("EEE · dd. MMM");day.setFormat12Hour("EEE · dd. MMM");day.setTextSize(12);day.setTextColor(uiMuted());day.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);actions.addView(day,new LinearLayout.LayoutParams(0,dp(46),1));
        page.addView(actions,new LinearLayout.LayoutParams(-1,dp(54)));

        HorizontalScrollView typesScroll=new HorizontalScrollView(this);typesScroll.setHorizontalScrollBarEnabled(false);LinearLayout types=new LinearLayout(this);types.setGravity(Gravity.CENTER_VERTICAL);
        types.addView(filterTab("Alle","all",true,accent));types.addView(filterTab("Hefte",LibraryStore.TYPE_NOTEBOOK,true,accent));types.addView(filterTab("Bücher",LibraryStore.TYPE_BOOK,true,accent));types.addView(filterTab("Arbeitsblätter",LibraryStore.TYPE_WORKSHEET,true,accent));
        View fav=filterTab("Lesezeichen","bookmarks",true,accent);fav.setOnClickListener(v->showBookmarkChooser());types.addView(fav);typesScroll.addView(types);page.addView(typesScroll,new LinearLayout.LayoutParams(-1,dp(50)));

        HorizontalScrollView subScroll=new HorizontalScrollView(this);subScroll.setHorizontalScrollBarEnabled(false);LinearLayout subs=new LinearLayout(this);subs.setGravity(Gravity.CENTER_VERTICAL);subs.addView(subjectTab("Alle Fächer","all",accent));for(LibraryStore.Subject s:library.subjects())subs.addView(subjectTab(s.name,s.name,s.color));subScroll.addView(subs);page.addView(subScroll,new LinearLayout.LayoutParams(-1,dp(42)));

        TextView caption=text(searchQuery.isEmpty()?"DEINE MATERIALIEN":"SUCHE · "+searchQuery.toUpperCase(Locale.ROOT),10,uiMuted());caption.setLetterSpacing(.14f);caption.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);page.addView(caption,new LinearLayout.LayoutParams(-1,dp(40)));

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);GridLayout grid=new GridLayout(this);boolean land=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;int cols=land?3:2;grid.setColumnCount(cols);grid.setPadding(0,dp(8),0,dp(34));scroll.addView(grid);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        int itemW=Math.max(dp(190),(getResources().getDisplayMetrics().widthPixels-dp(70))/cols);
        ArrayList<LibraryStore.Item> items=library.all(false);int shown=0;long delay=0;
        for(LibraryStore.Item item:items){if(LibraryStore.TYPE_TIMETABLE.equals(item.type))continue;if(!"all".equals(typeFilter)&&!item.type.equals(typeFilter))continue;if(!"all".equals(subjectFilter)&&!item.subject.equalsIgnoreCase(subjectFilter))continue;if(!searchQuery.isEmpty()){String h=(item.title+" "+item.subject).toLowerCase(Locale.ROOT);if(!h.contains(searchQuery.toLowerCase(Locale.ROOT)))continue;}View card=addLibraryCard(grid,item,itemW);card.setAlpha(0f);card.setTranslationY(dp(14));card.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(230).start();delay=Math.min(240,delay+28);shown++;}
        AddCover add=new AddCover(this,accent);add.setOnClickListener(v->addChooser());GridLayout.LayoutParams ag=new GridLayout.LayoutParams();ag.width=itemW;ag.height=dp(218);grid.addView(add,ag);
        if(shown==0){TextView empty=text("Noch leer · tippe auf +",15,uiMuted());empty.setGravity(Gravity.CENTER);GridLayout.LayoutParams ep=new GridLayout.LayoutParams();ep.width=itemW*Math.max(1,cols-1);ep.height=dp(170);grid.addView(empty,ep);}
    }

    private View roundHeaderButton(String glyph,String label,View.OnClickListener l){TextView v=text(glyph,23,uiInk());v.setContentDescription(label);v.setBackground(rounded(uiSurface(),dp(17)));v.setElevation(dp(2));v.setOnClickListener(l);v.setOnHoverListener((x,e)->handleToolHover(x,e,label));return v;}

    private View libraryAction(String icon,String label,int accent,View.OnClickListener l){
        LinearLayout box=new LinearLayout(this);box.setGravity(Gravity.CENTER);box.setPadding(dp(12),0,dp(12),0);box.setBackground(rounded(uiSurface(),dp(18)));box.setElevation(dp(3));
        TextView i=text(icon,18,(accent&0x00ffffff)|0xff000000);box.addView(i,new LinearLayout.LayoutParams(dp(34),-1));TextView t=text(label,13,uiInk());t.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);box.addView(t,new LinearLayout.LayoutParams(0,-1,1));box.setOnClickListener(l);return box;
    }

    private View filterTab(String label,String value,boolean type,int accent){
        boolean active=type?value.equals(typeFilter):value.equalsIgnoreCase(subjectFilter);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(14),0,dp(14),0);
        TextView t=text(label,13,active?uiInk():uiMuted());if(active)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);box.addView(t,new LinearLayout.LayoutParams(-2,dp(35)));View line=new View(this);line.setBackgroundColor(active?((accent&0x00ffffff)|0xff000000):Color.TRANSPARENT);box.addView(line,new LinearLayout.LayoutParams(active?dp(26):dp(8),dp(3)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(44));lp.rightMargin=dp(3);box.setLayoutParams(lp);box.setOnClickListener(x->{if(type)typeFilter=value;else subjectFilter=value;showLibrary();});return box;
    }

    private View subjectTab(String label,String value,int accent){
        boolean active=value.equalsIgnoreCase(subjectFilter);TextView v=text(label,12,active?((accent&0x00ffffff)|0xff000000):uiMuted());v.setPadding(dp(12),0,dp(12),0);if(active){v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setBackground(rounded((accent&0x00ffffff)|(darkMode()?0x24000000:0x15000000),dp(15)));}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(32));lp.rightMargin=dp(5);v.setLayoutParams(lp);v.setOnClickListener(x->{subjectFilter=value;showLibrary();});return v;
    }

    private View addLibraryCard(GridLayout grid,LibraryStore.Item item,int itemW){
        FrameLayout holder=new FrameLayout(this);holder.setPadding(dp(7),dp(7),dp(7),dp(7));int kind=LibraryStore.TYPE_NOTEBOOK.equals(item.type)?0:LibraryStore.TYPE_BOOK.equals(item.type)?1:2;FolderView folder=new FolderView(this,item.title,kind,item.color,item.subject);holder.addView(folder,new FrameLayout.LayoutParams(-1,-1));GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=itemW;gp.height=dp(218);grid.addView(holder,gp);holder.setOnClickListener(v->animateOpen(folder,()->openItem(item)));holder.setOnLongClickListener(v->{itemActions(item);return true;});return holder;
    }

    private void animateOpen(View v,Runnable r){v.animate().scaleX(.975f).scaleY(.975f).translationY(dp(2)).setDuration(85).withEndAction(()->{v.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(130).start();r.run();}).start();}

    private class FolderView extends View {
        final String label,subject;final int kind,color;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        FolderView(Context c,String l,int k,int col,String sub){super(c);label=l==null?"":l;kind=k;color=col;subject=sub==null?"Allgemein":sub;p.setSubpixelText(true);p.setDither(true);p.setHinting(Paint.HINTING_ON);setLayerType(View.LAYER_TYPE_HARDWARE,null);setElevation(dp(7));setClipToOutline(false);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),m=dp(12),r=dp(26);int surface=uiRaised();
            // layered paper/folder depth without noisy borders
            p.setStyle(Paint.Style.FILL);p.setColor(darkMode()?0xff1c1f24:0xffebecea);c.drawRoundRect(m+dp(10),m+dp(12),w-m+dp(1),h-m+dp(2),r,r,p);
            p.setColor(surface);c.drawRoundRect(m,m,w-m,h-m,r,r,p);
            // subtle embossed monogram
            String mono=label.trim().isEmpty()?"·":label.trim().substring(0,1).toUpperCase(Locale.ROOT);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(dp(76));p.setTextAlign(Paint.Align.RIGHT);p.setColor(darkMode()?0x12ffffff:0x10000000);c.drawText(mono,w-m-dp(22),h*.70f,p);
            // accent lives only in two illuminated corner strokes
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(dp(4));p.setColor((color&0x00ffffff)|0xff000000);RectF ar=new RectF(m+dp(5),m+dp(5),w-m-dp(5),h-m-dp(5));c.drawArc(ar,202,40,false,p);c.drawArc(ar,22,40,false,p);
            p.setStrokeWidth(dp(9));p.setColor((color&0x00ffffff)|(darkMode()?0x22000000:0x17000000));c.drawArc(ar,205,34,false,p);c.drawArc(ar,25,34,false,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(dp(18));p.setColor(uiInk());String t=label.trim();if(t.length()>24)t=t.substring(0,24)+"…";c.drawText(t,m+dp(20),h*.49f,p);
            p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(dp(11));p.setColor(uiMuted());c.drawText(subject.toUpperCase(Locale.ROOT),m+dp(20),h*.66f,p);
            p.setTextAlign(Paint.Align.LEFT);p.setTextSize(dp(10));p.setColor((color&0x00ffffff)|0xff000000);c.drawText(kind==1?"BUCH":kind==2?"BLATT":"HEFT",m+dp(20),m+dp(24),p);
            p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(dp(18));p.setColor(uiMuted());c.drawText(kind==1?"▤":kind==2?"▧":"▱",w-m-dp(20),m+dp(26),p);
        }
    }

    private class AddCover extends View {final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final int accent;AddCover(Context c,int a){super(c);accent=a;setElevation(dp(2));} @Override protected void onDraw(Canvas c){int w=getWidth(),h=getHeight();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(darkMode()?0x445e626a:0x22706e69);c.drawRoundRect(dp(14),dp(14),w-dp(14),h-dp(14),dp(26),dp(26),p);p.setStyle(Paint.Style.FILL);p.setColor(uiSurface());c.drawCircle(w/2f,h/2f,dp(29),p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setStrokeCap(Paint.Cap.ROUND);p.setColor((accent&0x00ffffff)|0xff000000);c.drawLine(w/2f-dp(9),h/2f,w/2f+dp(9),h/2f,p);c.drawLine(w/2f,h/2f-dp(9),w/2f,h/2f+dp(9),p);}}

    private void searchDialog(){EditText e=new EditText(this);e.setSingleLine(true);e.setText(searchQuery);e.setHint("Titel, Fach, Überschrift oder Text suchen");new AlertDialog.Builder(this).setTitle("Suchen").setView(e).setPositiveButton("Suchen",(d,w)->{searchQuery=e.getText().toString().trim();if(searchQuery.isEmpty())showLibrary();else showSearchResults(searchQuery);}).setNeutralButton("Löschen",(d,w)->{searchQuery="";showLibrary();}).setNegativeButton("Abbrechen",null).show();}

    private static class SearchHit{LibraryStore.Item item;int page=-1;String label;SearchHit(LibraryStore.Item i,int p,String l){item=i;page=p;label=l;}}
    private void showSearchResults(String q){screen="search";closePdf();note=null;root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(14));root.addView(body,new FrameLayout.LayoutParams(-1,-1));LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,INK);back.setOnClickListener(v->showLibrary());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Suche: „"+q+"“",23,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(h);ScrollView sv=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sv.addView(list);body.addView(sv,new LinearLayout.LayoutParams(-1,0,1));String needle=q.toLowerCase(Locale.ROOT);ArrayList<SearchHit> hits=new ArrayList<>();for(LibraryStore.Item item:library.all(false)){if(LibraryStore.TYPE_TIMETABLE.equals(item.type))continue;String meta=(item.title+" "+item.subject).toLowerCase(Locale.ROOT);if(meta.contains(needle))hits.add(new SearchHit(item,-1,item.title+" · "+item.subject));if(LibraryStore.TYPE_NOTEBOOK.equals(item.type)||LibraryStore.TYPE_WORKSHEET.equals(item.type)){NotebookView n=new NotebookView(this,item.id);for(int p=0;p<n.getPageCount();p++){String pt=n.getPageTitle(p),txt=n.getPlainText(p);String all=(pt+" "+txt).toLowerCase(Locale.ROOT);if(!all.trim().isEmpty()&&all.contains(needle)){String snip=!pt.isEmpty()?pt:txt.replace('\n',' ');if(snip.length()>70)snip=snip.substring(0,70)+"…";hits.add(new SearchHit(item,p,item.title+" · Seite "+(p+1)+" · "+snip));}}}else if(LibraryStore.TYPE_BOOK.equals(item.type)){PdfBookAnalyzer.Result ar=PdfBookAnalyzer.cached(this,item.id,item.uri);if(ar!=null)for(PdfBookAnalyzer.Entry e:ar.entries)if(e.title.toLowerCase(Locale.ROOT).contains(needle))hits.add(new SearchHit(item,e.pageIndex,item.title+" · Seite "+(e.pageIndex+1)+" · "+e.title));}}if(hits.isEmpty()){TextView e=text("Keine Treffer in Titeln, Fächern, Seitentiteln oder Textfeldern.",15,MUTED);e.setPadding(dp(12),dp(50),dp(12),dp(12));list.addView(e);}for(SearchHit hit:hits){TextView row=text(hit.label,14,INK);row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setPadding(dp(14),0,dp(14),0);row.setBackground(rounded(Color.WHITE,dp(10)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,0,0,dp(6));list.addView(row,lp);row.setOnClickListener(v->{if(hit.page>=0&&LibraryStore.TYPE_BOOK.equals(hit.item.type)){openBook(hit.item);root.postDelayed(()->{if(pdf!=null)pdf.goToPage(hit.page);},90);}else if(hit.page>=0){openNotebook(hit.item,false,null);root.postDelayed(()->note.goToPage(hit.page),70);}else openItem(hit.item);});}}

    private void addChooser(){String[] a={"Heft","Buch / PDF","Arbeitsblatt scannen","Arbeitsblatt aus Bild","Arbeitsblatt aus PDF","Stundenplan"};new AlertDialog.Builder(this).setTitle("Neu").setItems(a,(d,w)->{if(w==0)newNotebookDialog(LibraryStore.TYPE_NOTEBOOK,false);else if(w==1)newBookDialog();else if(w==2)newWorksheetMeta(true,false);else if(w==3)newWorksheetMeta(false,false);else if(w==4)newWorksheetMeta(false,true);else newNotebookDialog(LibraryStore.TYPE_TIMETABLE,true);}).show();}

    private String[] subjectNames(){ArrayList<LibraryStore.Subject>s=library.subjects();String[] a=new String[s.size()];for(int i=0;i<s.size();i++)a[i]=s.get(i).name;return a;}
    private int subjectIndex(String name){String[] s=subjectNames();for(int i=0;i<s.length;i++)if(s[i].equalsIgnoreCase(name))return i;return 0;}

    private void newNotebookDialog(String type,boolean timetable){LinearLayout box=formBox();EditText title=new EditText(this);title.setHint(timetable?"z. B. Berufsschule":"z. B. Mathematik");box.addView(title);Spinner subjects=new Spinner(this);String[] sn=subjectNames();subjects.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sn));box.addView(subjects);new AlertDialog.Builder(this).setTitle(timetable?"Stundenplan anlegen":"Neues Heft").setView(box).setPositiveButton("Anlegen",(d,w)->{String sub=sn[subjects.getSelectedItemPosition()];LibraryStore.Item i=library.add(type,title.getText().toString(),sub,library.subjectColor(sub),"");if(timetable){NotebookView n=new NotebookView(this,i.id);n.setPaper(7);n.save();showTimetables();}else{showLibrary();root.postDelayed(()->openItem(i),80);}}).setNegativeButton("Abbrechen",null).show();}

    private void newBookDialog(){LinearLayout box=formBox();EditText title=new EditText(this);title.setHint("Titel des Buchs");box.addView(title);Spinner subjects=new Spinner(this);String[] sn=subjectNames();subjects.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sn));box.addView(subjects);new AlertDialog.Builder(this).setTitle("Buch importieren").setView(box).setPositiveButton("PDF wählen",(d,w)->{pendingTitle=title.getText().toString().trim();pendingSubject=sn[subjects.getSelectedItemPosition()];pendingColor=0xff6a4d3b;pickDocument("application/pdf",REQ_BOOK_PDF);}).setNegativeButton("Abbrechen",null).show();}

    private void newWorksheetMeta(boolean camera,boolean pdfImport){LinearLayout box=formBox();EditText title=new EditText(this);title.setHint("Arbeitsblatt");box.addView(title);Spinner subjects=new Spinner(this);String[] sn=subjectNames();subjects.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sn));box.addView(subjects);new AlertDialog.Builder(this).setTitle(camera?"Arbeitsblatt scannen":"Arbeitsblatt importieren").setView(box).setPositiveButton(camera?"Kamera öffnen":"Datei wählen",(d,w)->{pendingTitle=title.getText().toString().trim();pendingSubject=sn[subjects.getSelectedItemPosition()];pendingColor=library.subjectColor(pendingSubject);if(camera)captureWorksheet();else pickDocument(pdfImport?"application/pdf":"image/*",pdfImport?REQ_SHEET_PDF:REQ_SHEET_IMAGE);}).setNegativeButton("Abbrechen",null).show();}

    private LinearLayout formBox(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(18),dp(8),dp(18),0);return b;}

    private void itemActions(LibraryStore.Item item){String[] a={"Umbenennen","Fach ändern","Coverfarbe ändern","Duplizieren","In Papierkorb"};new AlertDialog.Builder(this).setTitle(item.title).setItems(a,(d,w)->{if(w==0)renameItem(item);else if(w==1)changeSubject(item);else if(w==2)changeColor(item);else if(w==3)duplicateLibraryItem(item);else{library.trash(item.id);showLibrary();}}).show();}
    private void renameItem(LibraryStore.Item item){EditText e=new EditText(this);e.setText(item.title);e.setSelection(e.length());new AlertDialog.Builder(this).setTitle("Umbenennen").setView(e).setPositiveButton("Speichern",(d,w)->{item.title=e.getText().toString().trim();library.update(item);showLibrary();}).setNegativeButton("Abbrechen",null).show();}
    private void changeSubject(LibraryStore.Item item){String[] s=subjectNames();new AlertDialog.Builder(this).setTitle("Fach").setSingleChoiceItems(s,subjectIndex(item.subject),(d,w)->{item.subject=s[w];item.color=library.subjectColor(item.subject);library.update(item);d.dismiss();showLibrary();}).show();}
    private void changeColor(LibraryStore.Item item){int[] cs={0xff3157d5,0xff16875b,0xffd84b5b,0xffd38324,0xff7b4bc4,0xff3d6b7d,0xff59544d};String[] n={"Blau","Grün","Rot","Orange","Violett","Petrol","Anthrazit"};new AlertDialog.Builder(this).setTitle("Coverfarbe").setItems(n,(d,w)->{item.color=cs[w];library.update(item);showLibrary();}).show();}
    private void duplicateLibraryItem(LibraryStore.Item item){LibraryStore.Item copy=library.add(item.type,item.title+" Kopie",item.subject,item.color,item.uri);if(LibraryStore.TYPE_NOTEBOOK.equals(item.type)||LibraryStore.TYPE_WORKSHEET.equals(item.type)||LibraryStore.TYPE_TIMETABLE.equals(item.type)){try{String sid=item.id.replaceAll("[^a-zA-Z0-9._-]","_"),did=copy.id.replaceAll("[^a-zA-Z0-9._-]","_");File src=new File(getFilesDir(),"notebook-v15-"+sid+".bin");if(!src.exists())src=new File(getFilesDir(),"notebook-v14-"+sid+".bin");if(!src.exists())src=new File(getFilesDir(),"notebook-v13-"+sid+".bin");if(!src.exists())src=new File(getFilesDir(),"notebook-v9-"+sid+".bin");File dst=new File(getFilesDir(),"notebook-v15-"+did+".bin");copyFile(src,dst);}catch(Exception ignored){}}showLibrary();}

    private void openItem(LibraryStore.Item item){library.touch(item.id);if(LibraryStore.TYPE_BOOK.equals(item.type))openBook(item);else openNotebook(item,false,null);}

    // ---------------- NOTEBOOK WORKSPACE ----------------
    private void openNotebook(LibraryStore.Item item,boolean split,String bookUri){
        screen="notebook";
        closePdf();cancelTransientUi();currentItem=item;root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();note=new NotebookView(this,item.id);
        note.setPenWidth(prefs.getFloat("pen_width",4.2f));note.setMarkerWidth(prefs.getFloat("marker_width",18f));note.color=prefs.getInt("pen_color",Color.BLACK);note.setPressureMode(prefs.getInt("pressure_mode",2));note.setTiltEnabled(prefs.getBoolean("tilt_enabled",true));
        if(split&&bookUri!=null&&!bookUri.isEmpty())buildSplit(bookUri);else root.addView(note,new FrameLayout.LayoutParams(-1,-1));
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        pageInfo=text("",13,0xff47433e);pageInfo.setBackground(rounded(0xeefcfbf8,dp(17)));FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(210),dp(36),Gravity.TOP|Gravity.CENTER_HORIZONTAL);ip.topMargin=landscape?dp(7):dp(67);root.addView(pageInfo,ip);
        pageInfo.setOnLongClickListener(v->{showToc();return true;});
        note.setListener(new NotebookView.OnPageListener(){@Override public void changed(int p,int n,boolean b){String t=note.getPageTitle(p);pageInfo.setText("Seite "+(p+1)+" / "+n+(b?"  ★":"")+(t.isEmpty()?"":"  · "+t));updateNotebookDock();}@Override public void overviewRequested(){showNotebookOverview(false);}@Override public void textRequested(float x,float y){textInput(x,y);}@Override public void saved(){dirty=true;library.touch(item.id);updateNotebookDock();}});
        note.setInkListener(this::scheduleMathHint);if(prefs.getBoolean("math_hints",true))mathRecognizer.prepare(null);
        TextView plus=text("＋ Seite",13,Color.WHITE);plus.setBackground(rounded(item.color,dp(20)));plus.setElevation(dp(5));plus.setOnClickListener(v->addPageSmart());FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(88),dp(40),Gravity.RIGHT|Gravity.BOTTOM);pp.setMargins(0,0,dp(12),dp(12));root.addView(plus,pp);
        bubble=null;addNotebookToolDock(item.color);updateNotebookDock();
    }

    private void addPageSmart(){int tp=prefs.getInt("template_paper",-1);String bg=prefs.getString("template_bg","");if(prefs.getBoolean("template_default",false)&&tp>=0)note.addPageFromTemplate(tp,bg);else note.addPage();}

    private void buildSplit(String uri){LinearLayout split=new LinearLayout(this);boolean landscape=getResources().getConfiguration().orientation==2;split.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);pdf=new PdfPane(this,uri);pdf.setOverviewListener(this::showPdfOverview);pdf.setNavigatorListener(this::showBookNavigator);LinearLayout.LayoutParams a=landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1);split.addView(pdf,a);View divider=new View(this);divider.setBackgroundColor(0xff84858a);LinearLayout.LayoutParams dl=landscape?new LinearLayout.LayoutParams(dp(6),-1):new LinearLayout.LayoutParams(-1,dp(6));split.addView(divider,dl);LinearLayout.LayoutParams b=landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1);split.addView(note,b);divider.setOnTouchListener(new DividerDrag(split,landscape));root.addView(split,new FrameLayout.LayoutParams(-1,-1));}
    private class DividerDrag implements View.OnTouchListener{final LinearLayout l;final boolean h;DividerDrag(LinearLayout l,boolean h){this.l=l;this.h=h;}public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float total=h?l.getWidth():l.getHeight(),pos=h?e.getX()+v.getX():e.getY()+v.getY(),r=Math.max(.25f,Math.min(.75f,pos/Math.max(1,total)));((LinearLayout.LayoutParams)l.getChildAt(0).getLayoutParams()).weight=r;((LinearLayout.LayoutParams)l.getChildAt(2).getLayoutParams()).weight=1-r;l.requestLayout();}return true;}}

    private void textInput(float x,float y){EditText e=new EditText(this);e.setHint("Text – mit S Pen im Textfeld schreiben oder tippen");e.setMinLines(2);if(Build.VERSION.SDK_INT>=34)e.setAutoHandwritingEnabled(true);new AlertDialog.Builder(this).setTitle("Handschrift → Text / Textfeld").setView(e).setPositiveButton("Einfügen",(d,w)->note.addText(x,y,e.getText().toString())).setNegativeButton("Abbrechen",null).show();}

    // ---------------- V15 LOW-LATENCY ADAPTIVE TOOL DOCK ----------------
    private TextClock plainClock(int size){TextClock c=new TextClock(this);c.setFormat24Hour("HH:mm");c.setFormat12Hour("HH:mm");c.setTextSize(size);c.setTextColor(uiInk());c.setGravity(Gravity.CENTER);c.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));c.setContentDescription("Uhrzeit");return c;}
    private TextView dockButton(String glyph,String name,View.OnClickListener action){TextView v=text(glyph,19,uiInk());v.setContentDescription(name);v.setBackground(rounded(uiSurface(),dp(15)));v.setElevation(dp(1));v.setPadding(dp(5),0,dp(5),0);v.setOnClickListener(action);v.setOnHoverListener((x,e)->handleToolHover(x,e,name));return v;}
    private void styleDock(LinearLayout dock,int accent){dock.setGravity(Gravity.CENTER);dock.setPadding(dp(5),dp(5),dp(5),dp(5));dock.setBackground(roundedStroke(uiSurface(),dp(22),darkMode()?0x334f5661:0x18000000));dock.setElevation(dp(8));dock.setLayerType(View.LAYER_TYPE_HARDWARE,null);}
    private void addDockChild(LinearLayout dock,View v,boolean landscape,int main){LinearLayout.LayoutParams lp=landscape?new LinearLayout.LayoutParams(dp(52),main):new LinearLayout.LayoutParams(main,dp(50));lp.setMargins(dp(2),dp(2),dp(2),dp(2));dock.addView(v,lp);}

    private void setToolSelected(TextView v,boolean selected,int accent){if(v==null)return;v.setBackground(rounded(selected?((accent&0x00ffffff)|(darkMode()?0x33000000:0x19000000)):uiSurface(),dp(15)));v.setTextColor(selected?((accent&0x00ffffff)|0xff000000):uiInk());v.setScaleX(selected?1.05f:1f);v.setScaleY(selected?1.05f:1f);}

    private LinearLayout colorCluster(boolean book,boolean land,int accent){
        LinearLayout strip=new LinearLayout(this);strip.setOrientation(land?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);strip.setGravity(Gravity.CENTER);dockColors=strip;int[] cs={Color.BLACK,0xff3157d5,0xff16875b,0xffd64045,0xffff9f1c};
        for(int c:cs){TextView sw=text("●",land?18:20,c);sw.setTag(c);sw.setTextColor(c);sw.setGravity(Gravity.CENTER);sw.setOnClickListener(v->{int col=(Integer)v.getTag();if(book&&bookInk!=null){bookInk.setColor(col);prefs.edit().putInt("book_pen_color",col).apply();updateBookDock();}else if(note!=null){note.color=col;prefs.edit().putInt("pen_color",col).apply();updateNotebookDock();}});sw.setOnHoverListener((x,e)->handleToolHover(x,e,"Farbe"));strip.addView(sw,new LinearLayout.LayoutParams(land?dp(46):dp(36),land?dp(29):dp(44)));}
        return strip;
    }

    private void refreshColorCluster(int selected){if(dockColors==null)return;for(int i=0;i<dockColors.getChildCount();i++){View v=dockColors.getChildAt(i);if(!(v instanceof TextView)||!(v.getTag() instanceof Integer))continue;int c=(Integer)v.getTag();v.setAlpha(c==selected?1f:.48f);v.setScaleX(c==selected?1.12f:1f);v.setScaleY(c==selected?1.12f:1f);}}

    private void addNotebookToolDock(int accent){
        closeToolPanel();boolean land=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;activeToolDock=new LinearLayout(this);activeToolDock.setOrientation(land?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);styleDock(activeToolDock,accent);
        dockUndo=dockButton("↶","Rückgängig",v->{note.undo();updateNotebookDock();});dockRedo=dockButton("↷","Wiederholen",v->{note.redo();updateNotebookDock();});addDockChild(activeToolDock,dockUndo,land,dp(46));addDockChild(activeToolDock,dockRedo,land,dp(46));
        dockPen=dockButton("✎","Stift",v->{previousTool=note.tool;note.setTool(1);updateNotebookDock();});dockMarker=dockButton("▰","Textmarker",v->{previousTool=note.tool;note.setTool(3);updateNotebookDock();});dockEraser=dockButton("⌫","Radiergummi",v->{previousTool=note.tool;note.setTool(4);updateNotebookDock();});addDockChild(activeToolDock,dockPen,land,dp(46));addDockChild(activeToolDock,dockMarker,land,dp(46));addDockChild(activeToolDock,dockEraser,land,dp(46));
        LinearLayout colors=colorCluster(false,land,accent);addDockChild(activeToolDock,colors,land,land?dp(148):dp(188));
        dockThickness=new ThicknessSlider(this,false,land,accent);addDockChild(activeToolDock,dockThickness,land,land?dp(118):dp(150));
        TextView dockSplit=dockButton("⇄","Buch + Heft",v->toggleSplitBook());addDockChild(activeToolDock,dockSplit,land,dp(46));
        dockMore=dockButton("⋯","Mehr Werkzeuge",v->showNotebookMorePanel(accent));addDockChild(activeToolDock,dockMore,land,dp(46));
        FrameLayout.LayoutParams lp=land?new FrameLayout.LayoutParams(dp(66),-2,Gravity.LEFT|Gravity.CENTER_VERTICAL):new FrameLayout.LayoutParams(-2,dp(62),Gravity.TOP|Gravity.CENTER_HORIZONTAL);if(land)lp.leftMargin=dp(8);else lp.topMargin=dp(5);root.addView(activeToolDock,lp);updateNotebookDock();
    }

    private void addBookToolDock(int accent){
        closeToolPanel();boolean land=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;activeToolDock=new LinearLayout(this);activeToolDock.setOrientation(land?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);styleDock(activeToolDock,accent);
        dockUndo=dockButton("↶","Rückgängig",v->{if(bookAnnotations.undo()){bookInk.invalidate();dirty=true;}updateBookDock();});dockRedo=dockButton("↷","Wiederholen",v->{if(bookAnnotations.redo()){bookInk.invalidate();dirty=true;}updateBookDock();});addDockChild(activeToolDock,dockUndo,land,dp(46));addDockChild(activeToolDock,dockRedo,land,dp(46));
        dockPen=dockButton("✎","Stift",v->{bookInk.setTool(BookAnnotationView.TOOL_PEN);updateBookDock();});dockMarker=dockButton("▰","Textmarker",v->{bookInk.setTool(BookAnnotationView.TOOL_MARKER);updateBookDock();});dockEraser=dockButton("⌫","Radiergummi",v->{bookInk.setTool(BookAnnotationView.TOOL_ERASER);updateBookDock();});addDockChild(activeToolDock,dockPen,land,dp(46));addDockChild(activeToolDock,dockMarker,land,dp(46));addDockChild(activeToolDock,dockEraser,land,dp(46));
        LinearLayout colors=colorCluster(true,land,accent);addDockChild(activeToolDock,colors,land,land?dp(148):dp(188));
        dockThickness=new ThicknessSlider(this,true,land,accent);addDockChild(activeToolDock,dockThickness,land,land?dp(118):dp(150));
        TextView dockSplit=dockButton("⇄","Buch + Heft",v->chooseSplitNotebook());addDockChild(activeToolDock,dockSplit,land,dp(46));
        dockMore=dockButton("⋯","Mehr Werkzeuge",v->showBookMorePanel(accent));addDockChild(activeToolDock,dockMore,land,dp(46));
        FrameLayout.LayoutParams lp=land?new FrameLayout.LayoutParams(dp(66),-2,Gravity.LEFT|Gravity.CENTER_VERTICAL):new FrameLayout.LayoutParams(-2,dp(62),Gravity.TOP|Gravity.CENTER_HORIZONTAL);if(land)lp.leftMargin=dp(8);else lp.topMargin=dp(5);root.addView(activeToolDock,lp);updateBookDock();
    }

    private class ThicknessSlider extends View{
        final boolean book,vertical;final int accent;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float value=.35f;
        ThicknessSlider(Context c,boolean b,boolean v,int a){super(c);book=b;vertical=v;accent=a;setContentDescription("Stiftdicke");setBackground(rounded(uiSurface(),dp(15)));setOnHoverListener((x,e)->handleToolHover(x,e,"Stiftdicke"));setValueFromTool();}
        void setValueFromTool(){float w=value;if(book&&bookInk!=null){w=bookInk.getTool()==BookAnnotationView.TOOL_MARKER?bookInk.getMarkerWidth()/0.060f:bookInk.getPenWidth()/0.020f;}else if(note!=null){w=note.tool==3?note.getMarkerWidth()/54f:note.getPenWidth()/18f;}value=Math.max(.05f,Math.min(1f,w));invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(dp(3));p.setColor(darkMode()?0xff5a5e66:0xffd5d6d4);float x1=vertical?w/2f:dp(16),y1=vertical?dp(16):h/2f,x2=vertical?w/2f:w-dp(16),y2=vertical?h-dp(16):h/2f;c.drawLine(x1,y1,x2,y2,p);p.setColor((accent&0x00ffffff)|0xff000000);float pos=vertical?y2-(y2-y1)*value:x1+(x2-x1)*value;if(vertical)c.drawLine(x1,pos,x2,y2,p);else c.drawLine(x1,y1,pos,y2,p);p.setStyle(Paint.Style.FILL);c.drawCircle(vertical?w/2f:pos,vertical?pos:h/2f,dp(6),p);p.setStyle(Paint.Style.STROKE);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()!=MotionEvent.ACTION_DOWN&&e.getActionMasked()!=MotionEvent.ACTION_MOVE&&e.getActionMasked()!=MotionEvent.ACTION_UP)return true;float range=vertical?Math.max(1,getHeight()-dp(32)):Math.max(1,getWidth()-dp(32));value=vertical?1f-(e.getY()-dp(16))/range:(e.getX()-dp(16))/range;value=Math.max(.05f,Math.min(1f,value));apply();invalidate();return true;}
        void apply(){if(book&&bookInk!=null){if(bookInk.getTool()==BookAnnotationView.TOOL_MARKER){float x=.008f+value*(.060f-.008f);bookInk.setMarkerWidth(x);prefs.edit().putFloat("book_marker_width",x).apply();}else{float x=.0012f+value*(.020f-.0012f);bookInk.setPenWidth(x);prefs.edit().putFloat("book_pen_width",x).apply();}}else if(note!=null){if(note.tool==3){float x=8f+value*(54f-8f);note.setMarkerWidth(x);prefs.edit().putFloat("marker_width",x).apply();}else{float x=1.1f+value*(18f-1.1f);note.setPenWidth(x);prefs.edit().putFloat("pen_width",x).apply();}}}
    }

    private String widthLabel(boolean book){if(book&&bookInk!=null){float w=bookInk.getTool()==BookAnnotationView.TOOL_MARKER?bookInk.getMarkerWidth():bookInk.getPenWidth();return String.format(Locale.GERMANY,"%.1f",w*1000f);}if(note!=null){float w=note.tool==3?note.getMarkerWidth():note.getPenWidth();return Math.round(w)+"";}return "–";}
    private void adjustNotebookWidth(int dir){if(note==null)return;if(note.tool==3){float w=Math.max(8f,Math.min(54f,note.getMarkerWidth()+dir*2f));note.setMarkerWidth(w);prefs.edit().putFloat("marker_width",w).apply();}else{float w=Math.max(1f,Math.min(18f,note.getPenWidth()+dir));note.setPenWidth(w);prefs.edit().putFloat("pen_width",w).apply();}updateNotebookDock();}
    private void adjustBookWidth(int dir){if(bookInk==null)return;if(bookInk.getTool()==BookAnnotationView.TOOL_MARKER){float w=Math.max(.008f,Math.min(.060f,bookInk.getMarkerWidth()+dir*.003f));bookInk.setMarkerWidth(w);prefs.edit().putFloat("book_marker_width",w).apply();}else{float w=Math.max(.0012f,Math.min(.020f,bookInk.getPenWidth()+dir*.0012f));bookInk.setPenWidth(w);prefs.edit().putFloat("book_pen_width",w).apply();}updateBookDock();}
    private void updateNotebookDock(){if(note==null)return;int accent=currentItem==null?BLUE:currentItem.color;if(dockUndo!=null){dockUndo.setEnabled(note.canUndo());dockUndo.setAlpha(note.canUndo()?1f:.28f);}if(dockRedo!=null){dockRedo.setEnabled(note.canRedo());dockRedo.setAlpha(note.canRedo()?1f:.28f);}setToolSelected(dockPen,note.tool==1||note.tool==0||note.tool==2,accent);setToolSelected(dockMarker,note.tool==3,accent);setToolSelected(dockEraser,note.tool==4,accent);refreshColorCluster(note.color);if(dockThickness!=null)dockThickness.setValueFromTool();if(dockWidthLabel!=null)dockWidthLabel.setText(widthLabel(false));if(dockColorButton!=null)dockColorButton.setTextColor(note.color);}
    private void updateBookDock(){if(bookAnnotations==null||bookInk==null)return;int accent=currentItem==null?BLUE:currentItem.color;if(dockUndo!=null){dockUndo.setEnabled(bookAnnotations.canUndo());dockUndo.setAlpha(bookAnnotations.canUndo()?1f:.28f);}if(dockRedo!=null){dockRedo.setEnabled(bookAnnotations.canRedo());dockRedo.setAlpha(bookAnnotations.canRedo()?1f:.28f);}setToolSelected(dockPen,bookInk.getTool()==BookAnnotationView.TOOL_PEN,accent);setToolSelected(dockMarker,bookInk.getTool()==BookAnnotationView.TOOL_MARKER,accent);setToolSelected(dockEraser,bookInk.getTool()==BookAnnotationView.TOOL_ERASER,accent);refreshColorCluster(bookInk.getColor());if(dockThickness!=null)dockThickness.setValueFromTool();if(dockWidthLabel!=null)dockWidthLabel.setText(widthLabel(true));if(dockColorButton!=null)dockColorButton.setTextColor(bookInk.getColor());}
    private void closeToolPanel(){if(transientToolPanel!=null&&transientToolPanel.getParent()==root)root.removeView(transientToolPanel);transientToolPanel=null;}
    private void showToolStrip(LinearLayout strip,int accent){closeToolPanel();transientToolPanel=new FrameLayout(this);transientToolPanel.setBackground(roundedStroke(uiSurface(),dp(22),0x22000000));transientToolPanel.setElevation(dp(16));transientToolPanel.setPadding(dp(6),dp(5),dp(6),dp(5));transientToolPanel.addView(strip,new FrameLayout.LayoutParams(-2,dp(48)));boolean land=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,dp(58),land?Gravity.LEFT|Gravity.CENTER_VERTICAL:Gravity.TOP|Gravity.CENTER_HORIZONTAL);if(land)lp.leftMargin=dp(78);else lp.topMargin=dp(72);root.addView(transientToolPanel,lp);transientToolPanel.setAlpha(0f);transientToolPanel.setScaleX(.45f);transientToolPanel.setScaleY(.86f);transientToolPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();}
    private void showInlinePalette(boolean book,int accent){LinearLayout strip=new LinearLayout(this);strip.setGravity(Gravity.CENTER);int[] cs={Color.BLACK,0xff3157d5,0xff16875b,0xffd64045,0xffff9f1c,0xff7b4bc4,0xffe84c9b};for(int c:cs){TextView sw=text("●",26,c);sw.setTextColor(c);sw.setOnClickListener(v->{if(book&&bookInk!=null){bookInk.setColor(c);prefs.edit().putInt("book_pen_color",c).apply();updateBookDock();}else if(note!=null){note.color=c;prefs.edit().putInt("pen_color",c).apply();updateNotebookDock();}closeToolPanel();});strip.addView(sw,new LinearLayout.LayoutParams(dp(45),dp(45)));}showToolStrip(strip,accent);}
    private TextView stripAction(String glyph,String label,View.OnClickListener action){TextView v=text(glyph+"  "+label,12,uiInk());v.setPadding(dp(12),0,dp(12),0);v.setOnClickListener(action);v.setOnHoverListener((x,e)->handleToolHover(x,e,label));return v;}
    private void showNotebookMorePanel(int accent){LinearLayout s=new LinearLayout(this);s.setGravity(Gravity.CENTER);s.addView(stripAction("⌁","Lasso",v->{note.setTool(5);closeToolPanel();}));s.addView(stripAction("／","Lineal",v->{note.setTool(6);closeToolPanel();}));s.addView(stripAction("T","Text",v->{note.setTool(7);closeToolPanel();}));s.addView(stripAction("★","Lesezeichen",v->{note.toggleBookmark();closeToolPanel();}));s.addView(stripAction("✓","Prüfung",v->{toggleStudyMark(false);closeToolPanel();}));s.addView(stripAction("∑","Rechner",v->{showCalculator();closeToolPanel();}));s.addView(stripAction("☷","Inhalt",v->{showToc();closeToolPanel();}));s.addView(stripAction("▣","Einfügen",v->{pasteClipboardIntoNotebook();closeToolPanel();}));showToolStrip(s,accent);}
    private void showBookMorePanel(int accent){LinearLayout s=new LinearLayout(this);s.setGravity(Gravity.CENTER);s.addView(stripAction("N","Notiz",v->{bookInk.setTool(BookAnnotationView.TOOL_NOTE);closeToolPanel();}));s.addView(stripAction("⛶","Ausschnitt",v->{bookInk.setTool(BookAnnotationView.TOOL_CLIP);closeToolPanel();}));s.addView(stripAction("★","Lesezeichen",v->{bookAnnotations.toggleBookmark(pdf.getCurrentPage());bookInk.invalidate();dirty=true;updateBookDock();closeToolPanel();}));s.addView(stripAction("✓","Prüfung",v->{toggleStudyMark(true);closeToolPanel();}));s.addView(stripAction("∑","Rechner",v->{showCalculator();closeToolPanel();}));s.addView(stripAction("☷","Navigation",v->{showBookNavigator();closeToolPanel();}));s.addView(stripAction("↻","Drehen",v->{int pg=pdf.getCurrentPage();bookAnnotations.rotatePageClockwise(pg);pdf.rotateCurrentPageClockwise();bookInk.invalidate();dirty=true;updateBookDock();closeToolPanel();}));showToolStrip(s,accent);}
    private void addAmbientAccent(FrameLayout host,int accent){View glow=new View(this);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0x00ffffff,(accent&0x00ffffff)|0x1c000000,0x00ffffff});g.setCornerRadius(dp(60));glow.setBackground(g);glow.setAlpha(.45f);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(240),dp(80),Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=dp(2);host.addView(glow,0,lp);ObjectAnimator a=ObjectAnimator.ofFloat(glow,"translationX",-dp(26),dp(26));a.setDuration(6500);a.setRepeatCount(ValueAnimator.INFINITE);a.setRepeatMode(ValueAnimator.REVERSE);a.start();}

    // ---------------- RADIAL BUBBLE ----------------
    private class CornerBubble extends TextView{float downX,downY;long lastTap;CornerBubble(Context c){super(c);setText("✎");setTextSize(25);setTextColor(Color.WHITE);setGravity(Gravity.CENTER);setElevation(dp(13));setContentDescription("Werkzeuge");setOnHoverListener((v,e)->handleToolHover(v,e,"Werkzeuge"));setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){float dist=(float)Math.hypot(e.getRawX()-downX,e.getRawY()-downY);if(dist<dp(15)){long now=System.currentTimeMillis();if(now-lastTap<300){closeRadial();swapToPreviousTool();lastTap=0;}else{lastTap=now;togglePrimaryRadial();}}else snapToCorner(e.getRawX(),e.getRawY());return true;}return true;});}void snapToCorner(float rx,float ry){closeRadial();FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getLayoutParams();boolean right=rx>root.getWidth()/2f,bottom=ry>root.getHeight()/2f;lp.gravity=(right?Gravity.RIGHT:Gravity.LEFT)|(bottom?Gravity.BOTTOM:Gravity.TOP);lp.setMargins(dp(14),dp(14),dp(14),dp(14));setLayoutParams(lp);}void glyph(String s){setText(s);}}

    private void swapToPreviousTool(){if(note==null)return;int now=note.tool;note.setTool(previousTool);previousTool=now;bubble.glyph(note.tool==3?"▰":note.tool==4?"⌫":note.tool==5?"⌁":note.tool==6?"／":note.tool==7?"T":"✎");}

    private void togglePrimaryRadial(){if(!radialViews.isEmpty()){closeRadial();return;}showRadial(new String[]{"✎","▰","⌫","●","⋯"},new int[]{1,3,4,-1,-2},0);}
    private void showSecondaryRadial(){closeRadial();showRadial(new String[]{"↶","↷","⌁","／","T","⋯"},new int[]{-10,-11,5,6,7,-20},1);}
    private void showTertiaryRadial(){closeRadial();showRadial(new String[]{"▦","★","☷","∑","▣","⇄","⇩","♻"},new int[]{-12,-13,-16,-14,-19,-15,-17,-18},2);}

    /** Radial fan with a fixed center-to-center gap. The fan always grows into the screen. */
    private void showRadial(String[] glyphs,int[] actions,int level){
        if(bubble==null||glyphs==null||actions==null||glyphs.length!=actions.length)return;
        root.post(()->{
            float cx=bubble.getX()+bubble.getWidth()/2f,cy=bubble.getY()+bubble.getHeight()/2f;
            boolean right=cx>root.getWidth()/2f,bottom=cy>root.getHeight()/2f;
            int n=glyphs.length;float dot=dp(46),margin=dp(10),minGap=dp(58);
            float spanDeg=90f;
            float stepDeg=n<=1?90f:spanDeg/(n-1);
            float required=n<=1?dp(92):(float)(minGap/(2.0*Math.sin(Math.toRadians(stepDeg/2.0))));
            required=Math.max(required,dp(92+level*8));
            float availableX=right?cx-margin:root.getWidth()-cx-margin;
            float availableY=bottom?cy-margin:root.getHeight()-cy-margin;
            float maxRadius=Math.max(dp(70),Math.min(availableX,availableY)-dot/2f);
            float radius=Math.min(required,maxRadius);
            // If a very small display cannot fit the requested gap, widen only as much as needed.
            if(n>1&&radius<required){
                double needStep=2.0*Math.asin(Math.min(.999,minGap/(2.0*Math.max(1f,radius))));
                spanDeg=(float)Math.min(90.0,Math.toDegrees(needStep)*(n-1));
                stepDeg=spanDeg/(n-1);
            }
            float start;
            if(!right&&!bottom)start=0f;          // top-left: right -> down
            else if(right&&!bottom)start=90f;     // top-right: down -> left
            else if(right)start=180f;             // bottom-right: left -> up
            else start=270f;                      // bottom-left: up -> right
            for(int i=0;i<n;i++){
                float angle=start+(n==1?45f:stepDeg*i);
                double a=Math.toRadians(angle);
                float x=cx+(float)Math.cos(a)*radius-dot/2f;
                float y=cy+(float)Math.sin(a)*radius-dot/2f;
                x=Math.max(margin,Math.min(root.getWidth()-dot-margin,x));
                y=Math.max(margin,Math.min(root.getHeight()-dot-margin,y));
                TextView d=text(glyphs[i],19,Color.WHITE);d.setBackground(rounded(0xff343841,100));d.setElevation(dp(12));d.setAlpha(0);d.setScaleX(.65f);d.setScaleY(.65f);
                FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams((int)dot,(int)dot);lp.leftMargin=(int)x;lp.topMargin=(int)y;root.addView(d,lp);radialViews.add(d);
                final int action=actions[i];d.setOnClickListener(v->handleRadial(action));
                if(action==1)d.setOnLongClickListener(v->{penOptions(false);return true;});
                if(action==3)d.setOnLongClickListener(v->{penOptions(true);return true;});
                if(action==4)d.setOnLongClickListener(v->{eraserChooser();return true;});
                if(action==5)d.setOnLongClickListener(v->{if(note.copySelection())toast("Auswahl kopiert");return true;});
                final String hoverName=radialName(action);d.setOnHoverListener((v,e)->handleToolHover(v,e,hoverName));
                d.animate().alpha(1).scaleX(1).scaleY(1).setDuration(140+i*15L).start();
            }
        });
    }

    private String radialName(int a){switch(a){case 0:return "Bleistift";case 1:return "Stift";case 2:return "Füller";case 3:return "Textmarker";case 4:return "Radiergummi";case 5:return "Lasso";case 6:return "Lineal";case 7:return "Text";case -1:return "Farben";case -2:return "Mehr Werkzeuge";case -10:return "Rückgängig";case -11:return "Wiederholen";case -20:return "Weitere Funktionen";case -12:return "Papier";case -13:return "Lesezeichen";case -14:return "Taschenrechner";case -15:return "Buch + Heft";case -16:return "Inhaltsverzeichnis";case -17:return "Als PDF exportieren";case -18:return "Gelöschte Seite zurück";case -19:return "Zwischenablage einfügen";default:return "Werkzeug";}}
    private boolean handleToolHover(View anchor,MotionEvent e,String label){int i=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));int type=e.getToolType(i);if(type!=MotionEvent.TOOL_TYPE_STYLUS&&type!=MotionEvent.TOOL_TYPE_ERASER)return false;int a=e.getActionMasked();if(a==MotionEvent.ACTION_HOVER_ENTER||a==MotionEvent.ACTION_HOVER_MOVE){showHoverLabel(anchor,label);return true;}if(a==MotionEvent.ACTION_HOVER_EXIT){hideHoverLabel();return true;}return false;}
    private void showHoverLabel(View anchor,String label){if(root==null)return;hideHoverLabel();TextView t=text(label,12,Color.WHITE);t.setPadding(dp(10),dp(5),dp(10),dp(5));t.setBackground(rounded(0xe82b2e34,dp(12)));t.setElevation(dp(18));int[] a=new int[2],r=new int[2];anchor.getLocationOnScreen(a);root.getLocationOnScreen(r);int w=Math.max(dp(70),Math.min(dp(180),label.length()*dp(8)+dp(20)));int x=a[0]-r[0]+anchor.getWidth()/2-w/2;int y=a[1]-r[1]-dp(34);x=Math.max(dp(6),Math.min(root.getWidth()-w-dp(6),x));if(y<dp(6))y=a[1]-r[1]+anchor.getHeight()+dp(6);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,dp(30));lp.leftMargin=x;lp.topMargin=Math.max(dp(6),Math.min(root.getHeight()-dp(36),y));root.addView(t,lp);hoverLabel=t;}
    private void hideHoverLabel(){if(hoverLabel!=null&&hoverLabel.getParent()==root)root.removeView(hoverLabel);hoverLabel=null;}

    private void closeRadial(){hideHoverLabel();for(View v:new ArrayList<>(radialViews))if(v.getParent()==root)root.removeView(v);radialViews.clear();}
    private void handleRadial(int a){if(note==null){closeRadial();return;}if(a>=0){if(a==1&&note.tool==1){penOptions(false);closeRadial();return;}if(a==3&&note.tool==3){penOptions(true);closeRadial();return;}if(note.tool!=a){previousTool=note.tool;note.setTool(a);}bubble.glyph(a==3?"▰":a==4?"⌫":a==5?"⌁":a==6?"／":a==7?"T":"✎");closeRadial();return;}switch(a){case-1:palette();closeRadial();break;case-2:showSecondaryRadial();break;case-10:note.undo();closeRadial();break;case-11:note.redo();closeRadial();break;case-20:showTertiaryRadial();break;case-12:paperChooser();closeRadial();break;case-13:note.toggleBookmark();closeRadial();break;case-14:showCalculator();closeRadial();break;case-15:chooseSplitBook();closeRadial();break;case-16:showToc();closeRadial();break;case-17:exportCurrentPdf();closeRadial();break;case-18:if(!note.restoreLastDeletedPage())toast("Papierkorb ist leer");closeRadial();break;case-19:pasteClipboardIntoNotebook();closeRadial();break;}}

    private void penOptions(boolean marker){
        if(note==null)return;
        LinearLayout box=formBox();
        TextView preview=text("━━━━━━━━",marker?24:18,note.color);preview.setGravity(Gravity.CENTER);box.addView(preview,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView label=text(marker?"Markerbreite":"Stiftdicke",13,INK);label.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);box.addView(label,new LinearLayout.LayoutParams(-1,dp(28)));
        SeekBar seek=new SeekBar(this);int min=marker?8:1,max=marker?54:18;float cur=marker?note.getMarkerWidth():note.getPenWidth();seek.setMax(max-min);seek.setProgress(Math.round(cur)-min);box.addView(seek);
        LinearLayout presets=new LinearLayout(this);presets.setGravity(Gravity.CENTER);float[] pw=marker?new float[]{10,18,28,42}:new float[]{2,4,7,11};String[] pn=marker?new String[]{"Fein","Normal","Breit","XL"}:new String[]{"0.3","0.5","0.8","1.2"};for(int i=0;i<pw.length;i++){Button b=new Button(this);b.setText(pn[i]);b.setAllCaps(false);final float w=pw[i];b.setOnClickListener(v->{if(marker)note.setMarkerWidth(w);else note.setPenWidth(w);seek.setProgress(Math.round(w)-min);prefs.edit().putFloat(marker?"marker_width":"pen_width",w).apply();});presets.addView(b,new LinearLayout.LayoutParams(0,dp(42),1));}box.addView(presets);
        TextView colorTitle=text("Farbe",13,INK);colorTitle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);box.addView(colorTitle,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout colors=new LinearLayout(this);colors.setGravity(Gravity.CENTER);int[] cs={Color.BLACK,0xff3157d5,0xff16875b,0xffd64045,0xffff9f1c,0xff7b4bc4,0xffe84c9b};for(int c:cs){TextView x=text(note.color==c?"✓":"",18,Color.WHITE);x.setBackground(rounded(c,100));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(40),dp(40));lp.setMargins(dp(4),dp(4),dp(4),dp(4));colors.addView(x,lp);x.setOnClickListener(v->{note.color=c;prefs.edit().putInt("pen_color",c).apply();preview.setTextColor(c);bubble.setBackground(rounded(c,100));for(int j=0;j<colors.getChildCount();j++)((TextView)colors.getChildAt(j)).setText("");x.setText("✓");});}box.addView(colors);
        if(!marker){LinearLayout modes=new LinearLayout(this);String[] n={"Bleistift","Kugelschreiber","Füller"};int[] tools={0,1,2};for(int i=0;i<n.length;i++){Button b=new Button(this);b.setText(n[i]);b.setAllCaps(false);final int t=tools[i];b.setOnClickListener(v->{previousTool=note.tool;note.setTool(t);bubble.glyph("✎");});modes.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));}box.addView(modes);}
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean from){float w=p+min;if(marker)note.setMarkerWidth(w);else note.setPenWidth(w);preview.setText(marker?"━━━━━━━━":"━━━━━━━━");preview.setTextSize(Math.max(14,Math.min(30,marker?w*.55f:w*1.15f+13)));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){prefs.edit().putFloat(marker?"marker_width":"pen_width",marker?note.getMarkerWidth():note.getPenWidth()).apply();}});
        new AlertDialog.Builder(this).setTitle(marker?"Textmarker":"Stift").setView(box).setNegativeButton("Fertig",null).show();
    }

    private void eraserChooser(){String[] a={"Teilradierer – nur berührte Stelle","Strichradierer – kompletter Strich"};new AlertDialog.Builder(this).setTitle("Radiergummi").setSingleChoiceItems(a,note.eraserMode,(d,w)->{note.setEraserMode(w);bubble.glyph("⌫");d.dismiss();closeRadial();}).show();}
    private void palette(){int[] cs={Color.BLACK,0xff3157d5,0xff16875b,0xffd64045,0xffff9f1c,0xff7b4bc4,0xffe84c9b};LinearLayout r=new LinearLayout(this);r.setPadding(dp(8),dp(8),dp(8),dp(8));for(int c:cs){TextView x=text("",1,c);x.setBackground(rounded(c,100));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(42),dp(42));p.setMargins(dp(4),dp(4),dp(4),dp(4));r.addView(x,p);x.setOnClickListener(v->{previousTool=note.tool;note.color=c;note.setTool(1);prefs.edit().putInt("pen_color",c).apply();bubble.glyph("✎");bubble.setBackground(rounded(c,100));});}new AlertDialog.Builder(this).setTitle("Schnellfarben").setView(r).setNegativeButton("Schließen",null).show();}
    private void paperChooser(){String[] a={"Kariert","Liniert mit Rand","Gepunktet","Groß kariert","Eng liniert","Technisches Raster","Blanko","Stundenplan-Raster","Meine Vorlage anwenden"};new AlertDialog.Builder(this).setTitle("Papier").setItems(a,(d,w)->{if(w<8)note.setPaper(w);else{int p=prefs.getInt("template_paper",-1);if(p<0)toast("Noch keine Vorlage gespeichert");else{note.setPaper(p);String bg=prefs.getString("template_bg","");if(!bg.isEmpty())note.setBackgroundPath(note.getCurrentPageIndex(),bg);}}}).show();}

    // ---------------- PAGE OVERVIEW / TOC ----------------
    private void showNotebookOverview(boolean onlyBookmarks){if(note==null||overviewOverlay!=null)return;closeRadial();overviewOverlay=new FrameLayout(this);overviewOverlay.setBackgroundColor(0xfff3efe8);root.addView(overviewOverlay,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(12),dp(18),dp(12));overviewOverlay.addView(body,new FrameLayout.LayoutParams(-1,-1));LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(onlyBookmarks?"Lesezeichen":"Seitenübersicht",24,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);head.addView(title,new LinearLayout.LayoutParams(0,dp(54),1));TextView select=text("Auswählen",13,BLUE);select.setOnClickListener(v->multiPageSelect(onlyBookmarks));head.addView(select,new LinearLayout.LayoutParams(dp(90),dp(54)));TextView close=text("×",28,MUTED);close.setOnClickListener(v->closeOverview());head.addView(close,new LinearLayout.LayoutParams(dp(48),dp(54)));body.addView(head);ScrollView scroll=new ScrollView(this);GridLayout grid=new GridLayout(this);int cols=getResources().getConfiguration().orientation==2?5:3;grid.setColumnCount(cols);scroll.addView(grid);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));int iw=Math.max(dp(100),(getResources().getDisplayMetrics().widthPixels-dp(50))/cols);for(int i=0;i<note.getPageCount();i++){if(onlyBookmarks&&!note.isBookmarked(i))continue;final int idx=i;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);ImageView iv=new ImageView(this);iv.setImageBitmap(note.renderThumbnail(i,iw-dp(16),(int)((iw-dp(16))*1.414f)));iv.setScaleType(ImageView.ScaleType.FIT_CENTER);card.addView(iv,new LinearLayout.LayoutParams(iw-dp(12),(int)((iw-dp(12))*1.414f)));String t=note.getPageTitle(i);TextView lab=text((t.isEmpty()?"Seite "+(i+1):t)+(note.isBookmarked(i)?"  ★":"")+(note.isLocked(i)?"  🔒":""),11,0xff55514b);lab.setMaxLines(1);card.addView(lab,new LinearLayout.LayoutParams(-1,dp(28)));GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=iw;grid.addView(card,gp);card.setOnClickListener(v->{note.goToPage(idx);closeOverview();});card.setOnLongClickListener(v->{pageActions(idx,onlyBookmarks);return true;});}if(grid.getChildCount()==0){TextView empty=text("Noch keine Lesezeichen.",16,MUTED);grid.addView(empty);}}

    private void pageActions(int index,boolean onlyBookmarks){String[] a={note.isBookmarked(index)?"Lesezeichen entfernen":"Lesezeichen setzen","Seitentitel / Inhaltsverzeichnis","Seite duplizieren","Seite verschieben",note.isLocked(index)?"Seite entsperren":"Seite sperren","Als eigene Vorlage speichern","Seite löschen"};new AlertDialog.Builder(this).setTitle("Seite "+(index+1)).setItems(a,(d,w)->{if(w==0)note.toggleBookmark(index);else if(w==1)pageTitleDialog(index);else if(w==2)note.duplicatePage(index);else if(w==3)movePageDialog(index);else if(w==4)note.setLocked(index,!note.isLocked(index));else if(w==5){prefs.edit().putInt("template_paper",note.getPaper(index)).putString("template_bg",note.getBackgroundPath(index)).putBoolean("template_default",true).apply();toast("Vorlage gespeichert");}else if(w==6)note.deletePage(index);closeOverview();showNotebookOverview(onlyBookmarks);}).show();}
    private void pageTitleDialog(int index){EditText e=new EditText(this);e.setText(note.getPageTitle(index));e.setHint("z. B. Wärmepumpe – Aufbau");new AlertDialog.Builder(this).setTitle("Überschrift").setView(e).setPositiveButton("Speichern",(d,w)->note.setPageTitle(index,e.getText().toString())).setNegativeButton("Abbrechen",null).show();}
    private void movePageDialog(int index){EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setHint("1 bis "+note.getPageCount());new AlertDialog.Builder(this).setTitle("Seite verschieben").setView(e).setPositiveButton("Verschieben",(d,w)->{try{int to=Integer.parseInt(e.getText().toString())-1;note.movePage(index,to);}catch(Exception ex){toast("Ungültige Seitenzahl");}}).setNegativeButton("Abbrechen",null).show();}
    private void multiPageSelect(boolean onlyBookmarks){String[] names=new String[note.getPageCount()];boolean[] checked=new boolean[names.length];for(int i=0;i<names.length;i++)names[i]="Seite "+(i+1)+(note.getPageTitle(i).isEmpty()?"":" · "+note.getPageTitle(i));new AlertDialog.Builder(this).setTitle("Seiten auswählen").setMultiChoiceItems(names,checked,(d,w,is)->checked[w]=is).setPositiveButton("Aktionen",(d,w)->{ArrayList<Integer> ids=new ArrayList<>();for(int i=0;i<checked.length;i++)if(checked[i])ids.add(i);if(ids.isEmpty())return;String[] a={"Lesezeichen setzen","Löschen"};new AlertDialog.Builder(this).setTitle(ids.size()+" Seiten").setItems(a,(dd,ww)->{if(ww==0)for(int i:ids)if(!note.isBookmarked(i))note.toggleBookmark(i);else{}if(ww==1)note.deletePages(ids);closeOverview();showNotebookOverview(onlyBookmarks);}).show();}).setNegativeButton("Abbrechen",null).show();}

    private void showToc(){if(note==null)return;ArrayList<Integer> idx=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();for(int i=0;i<note.getPageCount();i++){String t=note.getPageTitle(i);if(!t.isEmpty()){idx.add(i);labels.add((i+1)+"  "+t);}}if(labels.isEmpty()){toast("Noch keine Überschriften. Seiten lange drücken → Seitentitel.");return;}new AlertDialog.Builder(this).setTitle("Inhaltsverzeichnis").setItems(labels.toArray(new String[0]),(d,w)->note.goToPage(idx.get(w))).show();}
    private void closeOverview(){overviewGeneration++;clearOverviewBitmaps();if(overviewOverlay!=null&&overviewOverlay.getParent()==root)root.removeView(overviewOverlay);overviewOverlay=null;}

    private void showBookmarkChooser(){ArrayList<LibraryStore.Item> items=new ArrayList<>();for(LibraryStore.Item i:library.all(false))if(LibraryStore.TYPE_NOTEBOOK.equals(i.type)||LibraryStore.TYPE_WORKSHEET.equals(i.type)||LibraryStore.TYPE_BOOK.equals(i.type))items.add(i);if(items.isEmpty()){toast("Noch keine Hefte oder Bücher");return;}String[] names=new String[items.size()];for(int i=0;i<items.size();i++){LibraryStore.Item it=items.get(i);names[i]=(LibraryStore.TYPE_BOOK.equals(it.type)?"Buch · ":"Heft · ")+it.title;}new AlertDialog.Builder(this).setTitle("Lesezeichen & Favoriten aus …").setItems(names,(d,w)->{LibraryStore.Item i=items.get(w);if(LibraryStore.TYPE_BOOK.equals(i.type)){openBook(i);root.postDelayed(this::showBookFavorites,100);}else{openNotebook(i,false,null);root.postDelayed(()->showNotebookOverview(true),80);}}).show();}

    // ---------------- BOOK / PDF ----------------
    private void openBook(LibraryStore.Item item){
        screen="book";closePdf();cancelTransientUi();currentItem=item;root=new FrameLayout(this);root.setBackgroundColor(darkMode()?0xff111317:0xffe6e7e8);setContentView(root);applySystemBars();note=null;
        bookAnnotations=new BookAnnotationStore(this,item.id);
        pdf=new PdfPane(this,item.uri);pdf.setOverviewListener(this::showPdfOverview);pdf.setNavigatorListener(this::showBookNavigator);root.addView(pdf,new FrameLayout.LayoutParams(-1,-1));
        bookInk=new BookAnnotationView(this,pdf,bookAnnotations);bookInk.setColor(prefs.getInt("book_pen_color",Color.BLACK));bookInk.setPenWidth(prefs.getFloat("book_pen_width",.0040f));bookInk.setMarkerWidth(prefs.getFloat("book_marker_width",.020f));bookInk.setPressureMode(prefs.getInt("pressure_mode",2));bookInk.setTiltEnabled(prefs.getBoolean("tilt_enabled",true));
        bookInk.setListener(new BookAnnotationView.Listener(){@Override public void noteRequested(float x,float y){bookNoteDialog(x,y);}@Override public void regionSelected(RectF r){bookClipActions(r);}@Override public void changed(){dirty=true;updateBookDock();}});
        root.addView(bookInk,new FrameLayout.LayoutParams(-1,-1));pdf.setTransformListener(()->{if(bookInk!=null)bookInk.invalidate();});pdf.setPageChangedListener((page,count)->{if(bookInk!=null){bookInk.clearSelection();bookInk.invalidate();}updateBookDock();});PdfBookAnalyzer.analyze(this,item.id,item.uri,false,r->{ });
        TextView back=text("‹",34,uiInk());back.setBackground(rounded(0xaaffffff,100));back.setOnClickListener(v->showLibrary());FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM);bp.setMargins(0,0,dp(12),dp(12));root.addView(back,bp);
        TextView name=text(item.title,14,uiInk());name.setBackground(rounded(0xccffffff,dp(16)));name.setPadding(dp(14),0,dp(14),0);name.setOnClickListener(v->showBookNavigator());FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,dp(36),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);np.bottomMargin=dp(14);root.addView(name,np);
        bookBubble=null;addBookToolDock(item.color);updateBookDock();
    }

    private class BookBubble extends TextView {
        float downX,downY;long lastTap;
        BookBubble(Context c){super(c);setText("✎");setTextSize(24);setTextColor(Color.WHITE);setGravity(Gravity.CENTER);setElevation(dp(13));setContentDescription("Buchwerkzeuge");setOnHoverListener((v,e)->handleToolHover(v,e,"Buchwerkzeuge"));setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){float d=(float)Math.hypot(e.getRawX()-downX,e.getRawY()-downY);if(d<dp(15))toggleBookRadial();else snap(e.getRawX(),e.getRawY());return true;}return true;});}
        void snap(float rx,float ry){closeRadial();FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getLayoutParams();boolean right=rx>root.getWidth()/2f,bottom=ry>root.getHeight()/2f;lp.gravity=(right?Gravity.RIGHT:Gravity.LEFT)|(bottom?Gravity.BOTTOM:Gravity.TOP);lp.setMargins(dp(14),dp(14),dp(14),dp(14));setLayoutParams(lp);}
        void glyph(String s){setText(s);}
    }

    private void toggleBookRadial(){if(bookInk==null||bookBubble==null)return;if(!radialViews.isEmpty()){closeRadial();return;}showBookRadial(new String[]{"✎","▰","⌫","N","⛶","★","⋯"},new int[]{1,2,3,4,5,6,-1},0);}
    private void showBookMoreRadial(){closeRadial();showBookRadial(new String[]{"∑","★+","N+","☷"},new int[]{10,11,12,13},1);}
    private void showBookRadial(String[] glyphs,int[] actions,int level){
        if(bookBubble==null||glyphs.length!=actions.length)return;root.post(()->{float cx=bookBubble.getX()+bookBubble.getWidth()/2f,cy=bookBubble.getY()+bookBubble.getHeight()/2f;boolean right=cx>root.getWidth()/2f,bottom=cy>root.getHeight()/2f;int n=glyphs.length;float dot=dp(44),margin=dp(9),minGap=dp(56),span=90f,step=n<=1?90f:span/(n-1);float required=n<=1?dp(92):(float)(minGap/(2.0*Math.sin(Math.toRadians(step/2.0))));required=Math.max(required,dp(94+level*10));float ax=right?cx-margin:root.getWidth()-cx-margin,ay=bottom?cy-margin:root.getHeight()-cy-margin;float maxRadius=Math.max(dp(72),Math.min(ax,ay)-dot/2f);float radius=Math.min(required,maxRadius);if(n>1&&radius<required){double need=2.0*Math.asin(Math.min(.999,minGap/(2.0*Math.max(1f,radius))));span=(float)Math.min(90.0,Math.toDegrees(need)*(n-1));step=span/(n-1);}float start=!right&&!bottom?0f:right&&!bottom?90f:right?180f:270f;for(int i=0;i<n;i++){float angle=start+(n==1?45f:step*i);double a=Math.toRadians(angle);float x=cx+(float)Math.cos(a)*radius-dot/2f,y=cy+(float)Math.sin(a)*radius-dot/2f;x=Math.max(margin,Math.min(root.getWidth()-dot-margin,x));y=Math.max(margin,Math.min(root.getHeight()-dot-margin,y));TextView d=text(glyphs[i],17,Color.WHITE);d.setBackground(rounded(0xff343841,100));d.setElevation(dp(12));d.setAlpha(0);d.setScaleX(.7f);d.setScaleY(.7f);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams((int)dot,(int)dot);lp.leftMargin=(int)x;lp.topMargin=(int)y;root.addView(d,lp);radialViews.add(d);final int action=actions[i];d.setOnClickListener(v->handleBookTool(action));if(action==1)d.setOnLongClickListener(v->{bookToolOptions(false);return true;});if(action==2)d.setOnLongClickListener(v->{bookToolOptions(true);return true;});d.setOnHoverListener((v,e)->handleToolHover(v,e,bookToolName(action)));d.animate().alpha(1).scaleX(1).scaleY(1).setDuration(130+i*12L).start();}});
    }
    private String bookToolName(int a){switch(a){case 1:return "Stift";case 2:return "Textmarker";case 3:return "Radierer";case 4:return "Notiz";case 5:return "Ausschnitt / Favorit";case 6:return "Seite als Lesezeichen";case 10:return "Taschenrechner";case 11:return "Favoriten";case 12:return "Notizen";case 13:return "Buchnavigation";default:return "Mehr";}}
    private void handleBookTool(int a){if(bookInk==null)return;if(a==-1){showBookMoreRadial();return;}if(a==10){showCalculator();closeRadial();return;}if(a==11){showBookFavorites();closeRadial();return;}if(a==12){showBookNotes();closeRadial();return;}if(a==13){showBookNavigator();closeRadial();return;}if(a==6){bookAnnotations.toggleBookmark(pdf.getCurrentPage());dirty=true;toast(bookAnnotations.isBookmarked(pdf.getCurrentPage())?"Seite als Lesezeichen gespeichert":"Lesezeichen entfernt");bookInk.invalidate();closeRadial();return;}if((a==1&&bookInk.getTool()==BookAnnotationView.TOOL_PEN)||(a==2&&bookInk.getTool()==BookAnnotationView.TOOL_MARKER)){bookToolOptions(a==2);closeRadial();return;}bookInk.setTool(a);bookBubble.glyph(a==2?"▰":a==3?"⌫":a==4?"N":a==5?"⛶":"✎");closeRadial();}

    private void bookToolOptions(boolean marker){if(bookInk==null)return;LinearLayout box=formBox();TextView preview=new TextView(this);preview.setGravity(Gravity.CENTER);preview.setText("────────");preview.setTextColor(bookInk.getColor());preview.setTextSize(marker?28:20);box.addView(preview,new LinearLayout.LayoutParams(-1,dp(42)));TextView label=text(marker?"Markerbreite":"Stiftdicke",13,INK);label.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);box.addView(label,new LinearLayout.LayoutParams(-1,dp(30)));SeekBar seek=new SeekBar(this);int max=100;seek.setMax(max);float current=marker?bookInk.getMarkerWidth():bookInk.getPenWidth();float lo=marker?.008f:.0012f,hi=marker?.060f:.020f;seek.setProgress(Math.round((current-lo)/(hi-lo)*max));box.addView(seek);LinearLayout colors=new LinearLayout(this);colors.setGravity(Gravity.CENTER);int[] cs={Color.BLACK,0xff3157d5,0xff16875b,0xffd64045,0xffffa000,0xff7b4bc4,0xffe84c9b};for(int c:cs){TextView sw=text("",1,c);sw.setBackground(rounded(c,100));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(38),dp(38));lp.setMargins(dp(4),dp(5),dp(4),dp(5));colors.addView(sw,lp);sw.setOnClickListener(v->{bookInk.setColor(c);prefs.edit().putInt("book_pen_color",c).apply();preview.setTextColor(c);bookBubble.setBackground(rounded(c,100));});}box.addView(colors);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean from){float w=lo+(hi-lo)*(p/(float)max);if(marker)bookInk.setMarkerWidth(w);else bookInk.setPenWidth(w);float px=w*Math.max(1000,pdf.getBitmapWidth());preview.setText(marker?"▰  "+Math.round(px)+" px":"━━  "+String.format(Locale.GERMANY,"%.1f",px)+" px");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){prefs.edit().putFloat(marker?"book_marker_width":"book_pen_width",marker?bookInk.getMarkerWidth():bookInk.getPenWidth()).apply();}});new AlertDialog.Builder(this).setTitle(marker?"Textmarker":"Stift").setView(box).setNegativeButton("Fertig",null).show();}

    private void bookNoteDialog(float x,float y){EditText e=new EditText(this);e.setHint("Notiz zu dieser Stelle");e.setMinLines(3);if(Build.VERSION.SDK_INT>=34)e.setAutoHandwritingEnabled(true);new AlertDialog.Builder(this).setTitle("Buchnotiz").setView(e).setPositiveButton("Speichern",(d,w)->{bookAnnotations.addNote(pdf.getCurrentPage(),x,y,e.getText().toString());dirty=true;if(bookInk!=null)bookInk.invalidate();}).setNegativeButton("Abbrechen",null).show();}

    private void bookClipActions(RectF region){if(bookAnnotations==null||pdf==null)return;String[] a={"Als Favorit merken","In Zwischenablage kopieren","Direkt in ein Heft einfügen","Favorit + Zwischenablage"};new AlertDialog.Builder(this).setTitle("Ausschnitt").setItems(a,(d,w)->{if(w==0||w==3){bookAnnotations.addFavorite(pdf.getCurrentPage(),region,"Seite "+(pdf.getCurrentPage()+1)+" · Ausschnitt");dirty=true;}if(w==1||w==3)copyBookClipToClipboard(region);if(w==2)exportBookClipToNotebook(region);if(bookInk!=null)bookInk.clearSelection();}).setNegativeButton("Abbrechen",(d,w)->{if(bookInk!=null)bookInk.clearSelection();}).show();}

    private void copyBookClipToClipboard(RectF region){
        Bitmap crop=bookInk!=null?bookInk.cropWithAnnotations(region,1800):pdf.cropCurrentPage(region,1800);
        if(crop==null){toast("Ausschnitt konnte nicht erstellt werden");return;}
        try{
            File dir=new File(getFilesDir(),"book-clips");dir.mkdirs();
            File out=new File(dir,"clipboard-"+System.currentTimeMillis()+".png");
            try(FileOutputStream fos=new FileOutputStream(out)){crop.compress(Bitmap.CompressFormat.PNG,96,fos);}
            crop.recycle();
            prefs.edit().putString("clip_path",out.getAbsolutePath()).putString("clip_source",(currentItem==null?"Buch":currentItem.title)+" · S. "+(pdf.getCurrentPage()+1)).apply();
            toast("Ausschnitt in Zwischenablage");
        }catch(Exception e){toast("Zwischenablage fehlgeschlagen");}
    }

    private void pasteClipboardIntoNotebook(){
        if(note==null)return;String path=prefs.getString("clip_path","");
        if(path.isEmpty()||!new File(path).exists()){toast("Zwischenablage ist leer");return;}
        if(note.insertImage(path)){dirty=true;toast("Ausschnitt eingefügt · ziehen zum Verschieben · ↘ zum Skalieren · ⌁ zum Ankern");}
        else toast("Ausschnitt konnte nicht eingefügt werden");
    }

    private void exportBookClipToNotebook(RectF region){ArrayList<LibraryStore.Item> targets=new ArrayList<>();for(LibraryStore.Item i:library.all(false))if(LibraryStore.TYPE_NOTEBOOK.equals(i.type))targets.add(i);if(targets.isEmpty()){toast("Lege zuerst ein Heft an");return;}String[] names=new String[targets.size()];for(int i=0;i<names.length;i++)names[i]=targets.get(i).title+" · "+targets.get(i).subject;new AlertDialog.Builder(this).setTitle("Ausschnitt in Heft").setItems(names,(d,w)->{Bitmap crop=bookInk!=null?bookInk.cropWithAnnotations(region,1600):pdf.cropCurrentPage(region,1600);if(crop==null){toast("Ausschnitt konnte nicht erstellt werden");return;}try{File dir=new File(getFilesDir(),"book-clips");dir.mkdirs();File out=new File(dir,"clip-"+System.currentTimeMillis()+".png");int pw=(int)NotebookView.PAGE_W,ph=(int)NotebookView.PAGE_H;Bitmap sheet=Bitmap.createBitmap(pw,ph,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(sheet);c.drawColor(Color.WHITE);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float maxW=pw*.84f,maxH=ph*.62f,sc=Math.min(maxW/crop.getWidth(),maxH/crop.getHeight());sc=Math.min(1.4f,sc);float dw=crop.getWidth()*sc,dh=crop.getHeight()*sc,left=(pw-dw)/2f,top=170f;c.drawBitmap(crop,null,new RectF(left,top,left+dw,top+dh),p);p.setColor(0xff77736d);p.setTextSize(28);c.drawText((currentItem==null?"Buch":currentItem.title)+" · Seite "+(pdf.getCurrentPage()+1),80,110,p);try(FileOutputStream fos=new FileOutputStream(out)){sheet.compress(Bitmap.CompressFormat.PNG,95,fos);}crop.recycle();sheet.recycle();LibraryStore.Item target=targets.get(w);NotebookView n=new NotebookView(this,target.id);n.addPageFromTemplate(6,out.getAbsolutePath());n.setPageTitle(n.getCurrentPageIndex(),"Ausschnitt · "+(currentItem==null?"Buch":currentItem.title)+" S. "+(pdf.getCurrentPage()+1));n.save();library.touch(target.id);dirty=true;toast("Ausschnitt in „"+target.title+"“ eingefügt");}catch(Exception ex){toast("Einfügen fehlgeschlagen");}}).show();}

    private void showBookFavorites(){if(pdf==null||bookAnnotations==null)return;ArrayList<Integer> pages=new ArrayList<>();ArrayList<RectF> rects=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();for(int i=0;i<pdf.getPageCount();i++)if(bookAnnotations.isBookmarked(i)){pages.add(i);rects.add(null);labels.add("★ Seite "+(i+1));}for(BookAnnotationStore.Favorite f:bookAnnotations.favorites()){pages.add(f.page);rects.add(new RectF(f.rect));labels.add("◎ "+f.label+" · S. "+(f.page+1));}if(labels.isEmpty()){toast("Noch keine Buch-Lesezeichen oder Favoriten");return;}new AlertDialog.Builder(this).setTitle("Favoriten & Lesezeichen").setItems(labels.toArray(new String[0]),(d,w)->{int pg=pages.get(w);RectF r=rects.get(w);pdf.goToPage(pg);if(r!=null)pdf.postDelayed(()->pdf.focusNormalizedRect(r),120);}).setNegativeButton("Schließen",null).show();}

    private void showBookNotes(){
        if(pdf==null||bookAnnotations==null)return;ArrayList<Integer> pages=new ArrayList<>();ArrayList<BookAnnotationStore.Note> notes=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<pdf.getPageCount();i++)for(BookAnnotationStore.Note n:bookAnnotations.notesForPage(i)){pages.add(i);notes.add(n);String t=n.text.replace('\n',' ');if(t.length()>52)t=t.substring(0,52)+"…";labels.add("S. "+(i+1)+" · "+t);}
        if(labels.isEmpty()){toast("Noch keine Buchnotizen");return;}
        new AlertDialog.Builder(this).setTitle("Buchnotizen").setItems(labels.toArray(new String[0]),(d,w)->{
            int pg=pages.get(w);BookAnnotationStore.Note n=notes.get(w);
            new AlertDialog.Builder(this).setTitle(n.text).setItems(new String[]{"Zur Notiz springen","Notiz löschen"},(x,a)->{
                if(a==0){pdf.goToPage(pg);pdf.postDelayed(()->pdf.focusNormalizedRect(new RectF(Math.max(0,n.x-.08f),Math.max(0,n.y-.08f),Math.min(1,n.x+.08f),Math.min(1,n.y+.08f))),120);}
                else{bookAnnotations.removeNote(pg,n.id);dirty=true;if(bookInk!=null)bookInk.invalidate();toast("Notiz gelöscht");}
            }).show();
        }).setNegativeButton("Schließen",null).show();
    }

    private void showBookNavigator(){
        if(pdf==null)return;
        LinearLayout box=formBox();
        EditText pageField=new EditText(this);
        pageField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageField.setSingleLine(true);
        pageField.setHint("1 bis "+pdf.getPageCount());
        pageField.setText(Integer.toString(pdf.getCurrentPage()+1));
        pageField.setSelectAllOnFocus(true);
        box.addView(pageField,new LinearLayout.LayoutParams(-1,dp(54)));

        LinearLayout actions=new LinearLayout(this);
        Button toc=new Button(this);toc.setText("Inhaltsverzeichnis");
        Button overview=new Button(this);overview.setText("Seitenübersicht");
        Button rotate=new Button(this);rotate.setText("↻ Drehen");
        actions.addView(toc,new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(overview,new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(rotate,new LinearLayout.LayoutParams(0,dp(52),1));
        box.addView(actions);
        LinearLayout quick=new LinearLayout(this);
        Button bookmark=new Button(this);bookmark.setText(bookAnnotations!=null&&bookAnnotations.isBookmarked(pdf.getCurrentPage())?"★ Lesezeichen":"☆ Lesezeichen");
        Button favorites=new Button(this);favorites.setText("Favoriten");
        Button notes=new Button(this);notes.setText("Notizen");
        quick.addView(bookmark,new LinearLayout.LayoutParams(0,dp(48),1));quick.addView(favorites,new LinearLayout.LayoutParams(0,dp(48),1));quick.addView(notes,new LinearLayout.LayoutParams(0,dp(48),1));box.addView(quick);

        PdfBookAnalyzer.Result cached=currentItem==null?null:PdfBookAnalyzer.cached(this,currentItem.id,currentItem.uri);
        TextView analysis=text(cached==null?"Buchanalyse läuft automatisch …":cached.summary,12,MUTED);
        analysis.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        box.addView(analysis,new LinearLayout.LayoutParams(-1,dp(38)));

        TextView reanalyse=text("Neu analysieren",13,BLUE);
        reanalyse.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        box.addView(reanalyse,new LinearLayout.LayoutParams(-1,dp(38)));

        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Buchnavigation").setView(box).setPositiveButton("Öffnen",null).setNegativeButton("Schließen",null).create();
        dlg.setOnShowListener(x->{dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int p=Integer.parseInt(pageField.getText().toString().trim());if(p<1||p>pdf.getPageCount()){pageField.setError("1 bis "+pdf.getPageCount());return;}pdf.goToPage(p-1);dlg.dismiss();}catch(Exception e){pageField.setError("Seitenzahl eingeben");}});});
        toc.setOnClickListener(v->{dlg.dismiss();showPdfToc();});
        overview.setOnClickListener(v->{dlg.dismiss();showPdfOverview();});
        rotate.setOnClickListener(v->{int pg=pdf.getCurrentPage();if(bookAnnotations!=null)bookAnnotations.rotatePageClockwise(pg);pdf.rotateCurrentPageClockwise();if(bookInk!=null)bookInk.invalidate();pageField.setText(Integer.toString(pdf.getCurrentPage()+1));analysis.setText("Seite "+(pdf.getCurrentPage()+1)+" · Drehung "+pdf.getCurrentRotation()+"°");});
        rotate.setOnLongClickListener(v->{int old=pdf.getCurrentRotation(),pg=pdf.getCurrentPage();if(bookAnnotations!=null){int turns=((360-old)%360)/90;for(int i=0;i<turns;i++)bookAnnotations.rotatePageClockwise(pg);}pdf.resetCurrentPageRotation();if(bookInk!=null)bookInk.invalidate();analysis.setText("Seitendrehung zurückgesetzt");return true;});
        bookmark.setOnClickListener(v->{if(bookAnnotations!=null){bookAnnotations.toggleBookmark(pdf.getCurrentPage());dirty=true;bookmark.setText(bookAnnotations.isBookmarked(pdf.getCurrentPage())?"★ Lesezeichen":"☆ Lesezeichen");if(bookInk!=null)bookInk.invalidate();}});
        favorites.setOnClickListener(v->{dlg.dismiss();showBookFavorites();});
        notes.setOnClickListener(v->{dlg.dismiss();showBookNotes();});
        reanalyse.setOnClickListener(v->{
            if(currentItem==null)return;
            analysis.setText("Buch wird neu analysiert …");
            PdfBookAnalyzer.analyze(this,currentItem.id,currentItem.uri,true,r->{analysis.setText(r.error==null?r.summary:"Analyse fehlgeschlagen: "+r.error);});
        });
        dlg.show();
    }

    private void showPdfToc(){
        if(pdf==null||currentItem==null)return;
        final PdfPane target=pdf;
        final LibraryStore.Item book=currentItem;
        ProgressDialog progress=ProgressDialog.show(this,"Buchanalyse","Inhaltsverzeichnis und Kapitel werden analysiert …",true,false);
        PdfBookAnalyzer.analyze(this,book.id,book.uri,false,result->{
            try{progress.dismiss();}catch(Exception ignored){}
            if(pdf!=target||currentItem==null||!book.id.equals(currentItem.id))return;
            if(result.error!=null){
                new AlertDialog.Builder(this).setTitle("Inhaltsverzeichnis").setMessage("Analyse fehlgeschlagen: "+result.error).setPositiveButton("Seite wählen",(d,w)->showBookNavigator()).setNegativeButton("Schließen",null).show();
                return;
            }
            if(result.entries.isEmpty()){
                String msg=result.summary+(result.searchableText?"\n\nEs wurden keine sicheren Kapitelüberschriften erkannt.":"\n\nDas Buch besitzt vermutlich nur gescannte Seiten ohne Textschicht.");
                new AlertDialog.Builder(this).setTitle("Inhaltsverzeichnis").setMessage(msg).setPositiveButton("Seite wählen",(d,w)->showBookNavigator()).setNeutralButton("Neu analysieren",(d,w)->{PdfBookAnalyzer.invalidate(this,book.id,book.uri);showPdfToc();}).setNegativeButton("Schließen",null).show();
                return;
            }
            String[] labels=new String[result.entries.size()];
            for(int i=0;i<result.entries.size();i++){PdfBookAnalyzer.Entry e=result.entries.get(i);StringBuilder pad=new StringBuilder();for(int k=0;k<e.level;k++)pad.append("   ");labels[i]=pad+e.title+"   ·   "+(e.pageIndex+1);}
            new AlertDialog.Builder(this).setTitle("Inhaltsverzeichnis · "+result.source).setItems(labels,(d,w)->{PdfBookAnalyzer.Entry e=result.entries.get(w);target.goToPage(e.pageIndex);closeOverview();}).setNeutralButton("Neu analysieren",(d,w)->{PdfBookAnalyzer.invalidate(this,book.id,book.uri);showPdfToc();}).setNegativeButton("Schließen",null).show();
        });
    }

    private void showPdfOverview(){
        if(pdf==null||overviewOverlay!=null)return;closeRadial();overviewGeneration++;final int generation=overviewGeneration;overviewOverlay=new FrameLayout(this);overviewOverlay.setBackgroundColor(0xfff3efe8);root.addView(overviewOverlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(12),dp(18),dp(12));overviewOverlay.addView(body,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Buchseiten",24,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(54),1));TextView jump=text("Seite",13,BLUE);jump.setOnClickListener(v->showBookNavigator());h.addView(jump,new LinearLayout.LayoutParams(dp(64),dp(54)));TextView toc=text("☷",24,BLUE);toc.setContentDescription("Inhaltsverzeichnis");toc.setOnClickListener(v->showPdfToc());h.addView(toc,new LinearLayout.LayoutParams(dp(48),dp(54)));TextView x=text("×",28,MUTED);x.setOnClickListener(v->closeOverview());h.addView(x,new LinearLayout.LayoutParams(dp(50),dp(54)));body.addView(h);
        final int chunk=20;final int[] first={Math.max(0,(pdf.getCurrentPage()/chunk)*chunk)};LinearLayout nav=new LinearLayout(this);Button prev=new Button(this);prev.setText("‹");TextView range=text("",13,MUTED);Button next=new Button(this);next.setText("›");nav.addView(prev,new LinearLayout.LayoutParams(dp(64),dp(46)));nav.addView(range,new LinearLayout.LayoutParams(0,dp(46),1));nav.addView(next,new LinearLayout.LayoutParams(dp(64),dp(46)));body.addView(nav);
        ScrollView scroll=new ScrollView(this);GridLayout grid=new GridLayout(this);int cols=getResources().getConfiguration().orientation==2?5:3;grid.setColumnCount(cols);scroll.addView(grid);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));int iw=Math.max(dp(100),(getResources().getDisplayMetrics().widthPixels-dp(50))/cols);
        Runnable draw=()->renderPdfOverviewChunk(grid,range,first[0],chunk,cols,iw,generation);prev.setOnClickListener(v->{first[0]=Math.max(0,first[0]-chunk);draw.run();scroll.scrollTo(0,0);});next.setOnClickListener(v->{if(first[0]+chunk<pdf.getPageCount())first[0]+=chunk;draw.run();scroll.scrollTo(0,0);});draw.run();
    }

    private void renderPdfOverviewChunk(GridLayout grid,TextView range,int first,int chunk,int cols,int iw,int generation){
        if(pdf==null||overviewOverlay==null||generation!=overviewGeneration)return;grid.removeAllViews();clearOverviewBitmaps();int total=pdf.getPageCount(),end=Math.min(total,first+chunk);range.setText((first+1)+"–"+end+" / "+total);ArrayList<ImageView> slots=new ArrayList<>();ArrayList<Integer> indices=new ArrayList<>();
        for(int i=first;i<end;i++){final int idx=i;LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);ImageView iv=new ImageView(this);iv.setBackgroundColor(0xffdedcd6);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);int th=(int)((iw-dp(14))*1.414f);c.addView(iv,new LinearLayout.LayoutParams(iw-dp(14),th));c.addView(text("Seite "+(i+1)+(bookAnnotations!=null&&bookAnnotations.isBookmarked(i)?"  ★":""),11,MUTED),new LinearLayout.LayoutParams(-1,dp(28)));GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=iw;grid.addView(c,gp);c.setOnClickListener(v->{if(pdf!=null){pdf.goToPage(idx);closeOverview();}});slots.add(iv);indices.add(idx);}
        final PdfPane target=pdf;new Thread(()->{for(int j=0;j<indices.size();j++){if(generation!=overviewGeneration||target!=pdf)return;Bitmap b=target.renderThumbnail(indices.get(j),Math.max(84,iw-dp(14)),Math.max(118,(int)((iw-dp(14))*1.414f)));final int pos=j;runOnUiThread(()->{if(generation!=overviewGeneration||overviewOverlay==null||target!=pdf){if(b!=null&&!b.isRecycled())b.recycle();return;}overviewBitmaps.add(b);slots.get(pos).setImageBitmap(b);});}},"pdf-thumbs").start();
    }

    private void clearOverviewBitmaps(){for(Bitmap b:new ArrayList<>(overviewBitmaps))try{if(b!=null&&!b.isRecycled())b.recycle();}catch(Exception ignored){}overviewBitmaps.clear();}

    private void toggleSplitBook(){if(note==null)return;if(pdf!=null){openNotebook(currentItem,false,null);return;}chooseSplitBook();}
    private void chooseSplitBook(){ArrayList<LibraryStore.Item> books=new ArrayList<>();for(LibraryStore.Item i:library.all(false))if(LibraryStore.TYPE_BOOK.equals(i.type))books.add(i);if(books.isEmpty()){toast("Importiere zuerst ein Buch.");return;}String[] n=new String[books.size()];for(int i=0;i<n.length;i++)n[i]=books.get(i).title;new AlertDialog.Builder(this).setTitle("Buch neben Heft öffnen").setItems(n,(d,w)->openNotebook(currentItem,true,books.get(w).uri)).show();}
    private void chooseSplitNotebook(){if(currentItem==null||!LibraryStore.TYPE_BOOK.equals(currentItem.type)){toast("Öffne zuerst ein Buch.");return;}final String bookUri=currentItem.uri;ArrayList<LibraryStore.Item> notes=new ArrayList<>();for(LibraryStore.Item i:library.all(false))if(LibraryStore.TYPE_NOTEBOOK.equals(i.type))notes.add(i);if(notes.isEmpty()){toast("Lege zuerst ein Heft an.");return;}String[] n=new String[notes.size()];for(int i=0;i<n.length;i++)n[i]=notes.get(i).title;new AlertDialog.Builder(this).setTitle("Heft neben Buch öffnen").setItems(n,(d,w)->openNotebook(notes.get(w),true,bookUri)).show();}

    // ---------------- WORKSHEET IMPORT / SCAN ----------------
    private void pickDocument(String mime,int req){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,req);}
    private void captureWorksheet(){try{ContentValues cv=new ContentValues();cv.put(MediaStore.Images.Media.DISPLAY_NAME,"Schulranzen-Scan-"+System.currentTimeMillis()+".jpg");cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");cameraOutput=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);if(cameraOutput!=null)i.putExtra(MediaStore.EXTRA_OUTPUT,cameraOutput);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,REQ_SHEET_CAMERA);}catch(Exception e){toast("Kamera konnte nicht geöffnet werden");}}

    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK){if(req==REQ_SHEET_CAMERA&&cameraOutput!=null){try{getContentResolver().delete(cameraOutput,null,null);}catch(Exception ignored){}cameraOutput=null;}return;}try{
        if(req==REQ_BOOK_PDF&&data!=null&&data.getData()!=null){Uri u=data.getData();persist(u);importBookPdf(u);}
        else if(req==REQ_SHEET_IMAGE&&data!=null&&data.getData()!=null){Uri u=data.getData();persist(u);importWorksheetImage(u);}
        else if(req==REQ_SHEET_CAMERA){Uri u=cameraOutput;if(u==null&&data!=null&&data.getData()!=null)u=data.getData();cameraOutput=null;if(u!=null)importWorksheetImage(u);}
        else if(req==REQ_SHEET_PDF&&data!=null&&data.getData()!=null){Uri u=data.getData();persist(u);importWorksheetPdf(u);}
        else if(req==REQ_EXPORT&&data!=null&&data.getData()!=null&&pendingExport!=null){try(InputStream in=new FileInputStream(pendingExport);OutputStream out=getContentResolver().openOutputStream(data.getData())){copyStream(in,out);}toast("PDF exportiert");pendingExport=null;}
    }catch(Exception e){toast("Fehler: "+e.getClass().getSimpleName());}}
    private void persist(Uri u){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
    private void importBookPdf(Uri u){String title=pendingTitle.isEmpty()?nameFromUri(u,"Schulbuch"):pendingTitle;LibraryStore.Item book=library.add(LibraryStore.TYPE_BOOK,title,pendingSubject,pendingColor,"");ProgressDialog pd=new ProgressDialog(this);pd.setTitle("Buch importieren");pd.setMessage("PDF wird vollständig in den Schulranzen übernommen …");pd.setCancelable(false);pd.show();new Thread(()->{try{AppFileResolver.copyBook(this,u,book.id);book.uri=AppFileResolver.bookToken(book.id);library.update(book);PdfBookAnalyzer.analyze(this,book.id,book.uri,false,r->runOnUiThread(()->{if(pd.isShowing())pd.dismiss();showLibrary();toast("Buch vollständig importiert");}));}catch(Exception e){library.purge(book.id);runOnUiThread(()->{if(pd.isShowing())pd.dismiss();toast("Buch konnte nicht vollständig importiert werden");showLibrary();});}}).start();}
    private String nameFromUri(Uri u,String fallback){String n=u.getLastPathSegment();if(n==null||n.isEmpty())return fallback;return n.replaceAll("(?i)\\.(pdf|png|jpg|jpeg)$","");}

    private void importWorksheetImage(Uri uri){ProgressDialog progress=ProgressDialog.show(this,"Arbeitsblatt","Bild wird als A4-Arbeitsblatt vorbereitet …",true,false);new Thread(()->{try{LibraryStore.Item item=library.add(LibraryStore.TYPE_WORKSHEET,pendingTitle.isEmpty()?"Arbeitsblatt":pendingTitle,pendingSubject,pendingColor,"");File f=prepareA4Image(uri,item.id,0);NotebookView n=new NotebookView(this,item.id);ArrayList<String> p=new ArrayList<>();p.add(f.getAbsolutePath());n.addBackgroundPages(p,true);runOnUiThread(()->{progress.dismiss();showLibrary();root.postDelayed(()->openItem(item),60);});}catch(Exception e){runOnUiThread(()->{progress.dismiss();toast("Bildimport fehlgeschlagen");});}}).start();}

    private File prepareA4Image(Uri uri,String id,int index)throws Exception{Bitmap src=decodeBounded(uri,2400);if(src==null)throw new Exception("decode");Bitmap out=DocumentProcessor.toA4(src,1240,1754);File dir=new File(getFilesDir(),"worksheets");dir.mkdirs();File f=new File(dir,id+"-"+index+".jpg");try(FileOutputStream fos=new FileOutputStream(f)){out.compress(Bitmap.CompressFormat.JPEG,92,fos);}if(src!=out&&!src.isRecycled())src.recycle();if(out!=null&&!out.isRecycled())out.recycle();return f;}
    private Bitmap decodeBounded(Uri uri,int maxDim)throws Exception{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}int sample=1;int longest=Math.max(bounds.outWidth,bounds.outHeight);while(longest/sample>maxDim*2)sample*=2;BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=Math.max(1,sample);opts.inPreferredConfig=Bitmap.Config.ARGB_8888;try(InputStream in=getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,opts);}}

    private void importWorksheetPdf(Uri uri){ProgressDialog progress=ProgressDialog.show(this,"Arbeitsblatt","PDF-Seiten werden vorbereitet …",true,false);new Thread(()->{ParcelFileDescriptor fd=null;PdfRenderer renderer=null;try{LibraryStore.Item item=library.add(LibraryStore.TYPE_WORKSHEET,pendingTitle.isEmpty()?nameFromUri(uri,"Arbeitsblatt"):pendingTitle,pendingSubject,pendingColor,"");fd=getContentResolver().openFileDescriptor(uri,"r");renderer=new PdfRenderer(fd);ArrayList<String> paths=new ArrayList<>();File dir=new File(getFilesDir(),"worksheets");dir.mkdirs();for(int i=0;i<renderer.getPageCount();i++){PdfRenderer.Page pg=renderer.openPage(i);Bitmap b=Bitmap.createBitmap(1240,1754,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);float s=Math.min(1240f/pg.getWidth(),1754f/pg.getHeight());Matrix m=new Matrix();m.setScale(s,s);m.postTranslate((1240-pg.getWidth()*s)/2f,(1754-pg.getHeight()*s)/2f);pg.render(b,null,m,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);pg.close();File f=new File(dir,item.id+"-"+i+".jpg");try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,92,o);}b.recycle();paths.add(f.getAbsolutePath());}NotebookView n=new NotebookView(this,item.id);n.addBackgroundPages(paths,true);PdfRenderer rr=renderer;ParcelFileDescriptor ff=fd;renderer=null;fd=null;rr.close();ff.close();runOnUiThread(()->{progress.dismiss();showLibrary();root.postDelayed(()->openItem(item),60);});}catch(Exception e){try{if(renderer!=null)renderer.close();}catch(Exception ignored){}try{if(fd!=null)fd.close();}catch(Exception ignored){}runOnUiThread(()->{progress.dismiss();toast("PDF-Import fehlgeschlagen");});}}).start();}

    // ---------------- SHK TOOLS / EXAM MARKS ----------------
    private void showShkHome(){
        screen="shk";closePdf();cancelTransientUi();note=null;currentItem=null;
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(22),dp(16),dp(22),dp(18));root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,uiInk());back.setOnClickListener(v->showLibrary());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("SHK Werkzeugkasten",27,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));page.addView(h);
        TextView lead=text("Schnell im Unterricht rechnen, umrechnen und nachschlagen.",13,uiMuted());lead.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);page.addView(lead,new LinearLayout.LayoutParams(-1,dp(42)));
        ScrollView sv=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sv.addView(list);page.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        list.addView(shkHomeCard("∑","SHK-Schnellrechner","Volumenstrom, Leistung, Rohrinhalt, Fließgeschwindigkeit, Wasserenergie und Förderhöhe",v->showShkCalculator(SHKTools.FLOW_FROM_POWER)));
        list.addView(shkHomeCard("⇄","Einheiten-Assistent","bar, mWS, l/min, m³/h, kW, kWh, mm, Liter, Temperatur …",v->showUnitAssistant("Druck")));
        list.addView(shkHomeCard("ƒ","Formelsammlung","SHK- und Unterrichtsformeln mit Favoriten und direktem Rechner",v->showFormulaCollection()));
        list.addView(shkHomeCard("✓","Prüfungsmarkierungen",study.marks().size()+" gemerkte Seiten aus Heften und Büchern",v->showStudyMarks()));
    }

    private View shkHomeCard(String icon,String title,String sub,View.OnClickListener click){
        LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(16),dp(10),dp(16),dp(10));card.setBackground(roundedStroke(uiSurface(),dp(20),0x18000000));card.setElevation(dp(2));
        TextView i=text(icon,25,BLUE);card.addView(i,new LinearLayout.LayoutParams(dp(56),-1));LinearLayout tx=new LinearLayout(this);tx.setOrientation(LinearLayout.VERTICAL);TextView t=text(title,17,uiInk());t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.LEFT|Gravity.BOTTOM);tx.addView(t,new LinearLayout.LayoutParams(-1,dp(32)));TextView s=text(sub,12,uiMuted());s.setGravity(Gravity.LEFT|Gravity.TOP);tx.addView(s,new LinearLayout.LayoutParams(-1,dp(36)));card.addView(tx,new LinearLayout.LayoutParams(0,-1,1));TextView go=text("›",30,uiMuted());card.addView(go,new LinearLayout.LayoutParams(dp(38),-1));card.setOnClickListener(click);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(90));lp.setMargins(0,0,0,dp(10));card.setLayoutParams(lp);return card;
    }

    private void showShkCalculator(int mode){
        screen="shkcalc";closePdf();cancelTransientUi();note=null;
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(16),dp(22),dp(18));root.addView(p,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,uiInk());back.setOnClickListener(v->showShkHome());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("SHK-Schnellrechner",25,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));p.addView(h);
        TextView modeButton=text(SHKTools.calcNames()[Math.max(0,Math.min(mode,SHKTools.calcNames().length-1))]+"  ▾",15,uiInk());modeButton.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);modeButton.setPadding(dp(16),0,dp(16),0);modeButton.setBackground(roundedStroke(uiSurface(),dp(16),0x18000000));modeButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Berechnung wählen").setItems(SHKTools.calcNames(),(d,w)->showShkCalculator(w)).show());p.addView(modeButton,new LinearLayout.LayoutParams(-1,dp(54)));
        String[] labels=SHKTools.inputLabels(mode);EditText a=shkNumberField(labels[0]);EditText b=shkNumberField(labels[1]);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(58));fp.setMargins(0,dp(12),0,0);p.addView(a,fp);LinearLayout.LayoutParams fp2=new LinearLayout.LayoutParams(-1,dp(58));fp2.setMargins(0,dp(8),0,0);p.addView(b,fp2);
        if(mode==SHKTools.PRESSURE_HEAD){TextView info=text("Nur einen der beiden Werte eingeben; der andere wird berechnet.",12,uiMuted());info.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);p.addView(info,new LinearLayout.LayoutParams(-1,dp(38)));}
        TextView result=text("Ergebnis",28,BLUE);result.setTypeface(Typeface.DEFAULT,Typeface.BOLD);result.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);result.setPadding(dp(16),0,dp(16),0);result.setBackground(rounded(uiSurface(),dp(18)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(90));rp.setMargins(0,dp(16),0,dp(10));p.addView(result,rp);
        TextView formula=text(shkFormulaForMode(mode),14,uiMuted());formula.setGravity(Gravity.LEFT|Gravity.TOP);formula.setPadding(dp(4),dp(8),dp(4),0);p.addView(formula,new LinearLayout.LayoutParams(-1,dp(58)));
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence x,int st,int c,int af){}public void onTextChanged(CharSequence x,int st,int be,int co){updateShkCalc(mode,a,b,result);}public void afterTextChanged(Editable e){}};a.addTextChangedListener(watcher);b.addTextChangedListener(watcher);
    }

    private EditText shkNumberField(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(17);e.setTextColor(uiInk());e.setHintTextColor(uiMuted());e.setSingleLine(true);e.setPadding(dp(14),0,dp(14),0);e.setBackground(roundedStroke(uiSurface(),dp(15),0x18000000));e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);return e;}
    private double shkNumber(EditText e){String x=e.getText().toString().trim().replace(',','.');if(x.isEmpty())return 0;return Double.parseDouble(x);}
    private void updateShkCalc(int mode,EditText a,EditText b,TextView out){try{double av=shkNumber(a),bv=shkNumber(b);if(mode!=SHKTools.PRESSURE_HEAD&&(av<=0||bv<=0)){out.setText("Ergebnis");return;}if(mode==SHKTools.PRESSURE_HEAD&&av<=0&&bv<=0){out.setText("Ergebnis");return;}out.setText(SHKTools.calculate(mode,av,bv));}catch(Exception ex){out.setText("Eingabe prüfen");}}
    private String shkFormulaForMode(int m){for(SHKTools.Formula f:SHKTools.formulas())if(f.calculatorMode==m)return f.formula+"\n"+f.note;return "";}

    private void showUnitAssistant(String category){
        screen="shkunits";closePdf();cancelTransientUi();note=null;
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(16),dp(22),dp(18));root.addView(p,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,uiInk());back.setOnClickListener(v->showShkHome());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Einheiten-Assistent",25,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));p.addView(h);
        TextView categoryButton=text(category+"  ▾",15,uiInk());categoryButton.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);categoryButton.setPadding(dp(16),0,dp(16),0);categoryButton.setBackground(roundedStroke(uiSurface(),dp(16),0x18000000));categoryButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Größe wählen").setItems(SHKTools.categories(),(d,w)->showUnitAssistant(SHKTools.categories()[w])).show());p.addView(categoryButton,new LinearLayout.LayoutParams(-1,dp(54)));
        EditText value=shkNumberField("Wert");LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,dp(58));vp.setMargins(0,dp(14),0,dp(8));p.addView(value,vp);
        String[] units=SHKTools.units(category);LinearLayout selectors=new LinearLayout(this);Spinner from=new Spinner(this);Spinner to=new Spinner(this);ArrayAdapter<String> ua=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,units);from.setAdapter(ua);to.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,units));if(units.length>1)to.setSelection(1);selectors.addView(from,new LinearLayout.LayoutParams(0,dp(56),1));TextView arrow=text("→",22,uiMuted());selectors.addView(arrow,new LinearLayout.LayoutParams(dp(48),dp(56)));selectors.addView(to,new LinearLayout.LayoutParams(0,dp(56),1));p.addView(selectors);
        TextView result=text("Ergebnis",30,BLUE);result.setTypeface(Typeface.DEFAULT,Typeface.BOLD);result.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);result.setPadding(dp(16),0,dp(16),0);result.setBackground(rounded(uiSurface(),dp(18)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(92));rp.setMargins(0,dp(18),0,0);p.addView(result,rp);
        Runnable update=()->{try{String raw=value.getText().toString().trim().replace(',','.');if(raw.isEmpty()){result.setText("Ergebnis");return;}double v=Double.parseDouble(raw);double r=SHKTools.convert(category,(String)from.getSelectedItem(),(String)to.getSelectedItem(),v);result.setText(SHKTools.f(r)+" "+to.getSelectedItem());}catch(Exception ex){result.setText("Eingabe prüfen");}};
        value.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence x,int st,int c,int a){}public void onTextChanged(CharSequence x,int st,int b,int c){update.run();}public void afterTextChanged(Editable e){}});
        android.widget.AdapterView.OnItemSelectedListener l=new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){update.run();}public void onNothingSelected(android.widget.AdapterView<?> p){}};from.setOnItemSelectedListener(l);to.setOnItemSelectedListener(l);
    }

    private void showFormulaCollection(){
        screen="shkformula";closePdf();cancelTransientUi();note=null;
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(16),dp(22),dp(14));root.addView(p,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,uiInk());back.setOnClickListener(v->showShkHome());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Formelsammlung",25,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));p.addView(h);
        ScrollView sv=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sv.addView(list);p.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        java.util.Set<String> fav=prefs.getStringSet("shk_formula_favorites",java.util.Collections.emptySet());ArrayList<SHKTools.Formula> fs=SHKTools.formulas();fs.sort((a,b)->Boolean.compare(fav.contains(b.id),fav.contains(a.id)));
        for(SHKTools.Formula f:fs){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(8),dp(10),dp(8));row.setBackground(roundedStroke(uiSurface(),dp(17),0x16000000));LinearLayout txt=new LinearLayout(this);txt.setOrientation(LinearLayout.VERTICAL);TextView t=text(f.title,16,uiInk());t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);txt.addView(t,new LinearLayout.LayoutParams(-1,dp(30)));TextView eq=text(f.formula,15,BLUE);eq.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);txt.addView(eq,new LinearLayout.LayoutParams(-1,dp(30)));TextView meta=text(f.category+" · "+f.note,11,uiMuted());meta.setGravity(Gravity.LEFT|Gravity.TOP);txt.addView(meta,new LinearLayout.LayoutParams(-1,dp(34)));row.addView(txt,new LinearLayout.LayoutParams(0,-1,1));TextView star=text(fav.contains(f.id)?"★":"☆",25,fav.contains(f.id)?0xffd49a21:uiMuted());row.addView(star,new LinearLayout.LayoutParams(dp(48),-1));star.setOnClickListener(v->{java.util.Set<String> n=new HashSet<>(prefs.getStringSet("shk_formula_favorites",java.util.Collections.emptySet()));if(n.contains(f.id))n.remove(f.id);else n.add(f.id);prefs.edit().putStringSet("shk_formula_favorites",n).apply();showFormulaCollection();});row.setOnClickListener(v->{if(f.calculatorMode>=0)showShkCalculator(f.calculatorMode);else new AlertDialog.Builder(this).setTitle(f.title).setMessage(f.formula+"\n\n"+f.note).setPositiveButton("OK",null).show();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(112));lp.setMargins(0,0,0,dp(8));list.addView(row,lp);}
    }

    private void toggleStudyMark(boolean book){
        if(currentItem==null)return;int page=-1;RectF region=null;if(book&&pdf!=null){page=pdf.getCurrentPage();if(bookInk!=null)region=bookInk.getSelection();}else if(!book&&note!=null){page=note.getCurrentPageIndex();region=note.getLassoSelectionNormalized();}if(page<0)return;if(region!=null&&region.width()>.015f&&region.height()>.015f){study.addMark(currentItem.id,page,currentItem.title,currentItem.subject,"Gemerkter Ausschnitt",region.left,region.top,region.right,region.bottom);toast("Ausschnitt für Prüfung gemerkt · Seite "+(page+1));return;}StudyStore.Mark old=study.findPageMark(currentItem.id,page);if(old!=null){study.removeMark(old.id);toast("Prüfungsmarkierung entfernt");}else{study.addMark(currentItem.id,page,currentItem.title,currentItem.subject,"Für Prüfung merken");toast("Für Prüfung gemerkt · Seite "+(page+1));}
    }

    private void showStudyMarks(){
        screen="studymarks";closePdf();cancelTransientUi();note=null;
        root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(16),dp(22),dp(14));root.addView(p,new FrameLayout.LayoutParams(-1,-1));LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,uiInk());back.setOnClickListener(v->showStudyHome());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Prüfungsmarkierungen",25,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));p.addView(h);ScrollView sv=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sv.addView(list);p.addView(sv,new LinearLayout.LayoutParams(-1,0,1));ArrayList<StudyStore.Mark> marks=study.marks();if(marks.isEmpty()){TextView e=text("Noch nichts gemerkt. In einem Heft oder Buch: Mehr Werkzeuge → ✓ Prüfung.",15,uiMuted());e.setGravity(Gravity.LEFT);e.setPadding(dp(10),dp(40),dp(10),dp(10));list.addView(e);}for(StudyStore.Mark m:marks){LibraryStore.Item item=library.get(m.itemId);String label=(m.itemTitle.isEmpty()?"Material":m.itemTitle)+" · Seite "+(m.page+1)+(m.left>=0?" · Ausschnitt":"")+" · "+m.subject;TextView row=text(label,15,item==null?uiMuted():uiInk());row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setPadding(dp(14),0,dp(14),0);row.setBackground(roundedStroke(uiSurface(),dp(15),0x16000000));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(62));lp.setMargins(0,0,0,dp(7));list.addView(row,lp);row.setOnClickListener(v->{if(item==null){toast("Material nicht mehr vorhanden");return;}openMarkedItem(item,m);});row.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("Markierung entfernen?").setMessage(label).setPositiveButton("Entfernen",(d,w)->{study.removeMark(m.id);showStudyMarks();}).setNegativeButton("Abbrechen",null).show();return true;});}}
    private void openMarkedItem(LibraryStore.Item item,StudyStore.Mark mark){int page=mark==null?0:mark.page;if(LibraryStore.TYPE_BOOK.equals(item.type)){openBook(item);root.postDelayed(()->{if(pdf!=null){pdf.goToPage(page);if(mark!=null&&mark.left>=0)pdf.postDelayed(()->pdf.focusNormalizedRect(new RectF(mark.left,mark.top,mark.right,mark.bottom)),120);}},100);}else{openNotebook(item,false,null);root.postDelayed(()->{if(note!=null){note.goToPage(page);if(mark!=null&&mark.left>=0)note.postDelayed(()->note.focusNormalizedRect(new RectF(mark.left,mark.top,mark.right,mark.bottom)),80);}},80);}}

    // ---------------- TIMETABLE ----------------
    private void showTimetables(){screen="timetables";closePdf();note=null;root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(16),dp(22),dp(14));root.addView(body,new FrameLayout.LayoutParams(-1,-1));LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,INK);back.setOnClickListener(v->showLibrary());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Stundenpläne",26,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));TextView plus=text("＋",28,BLUE);plus.setOnClickListener(v->newNotebookDialog(LibraryStore.TYPE_TIMETABLE,true));h.addView(plus,new LinearLayout.LayoutParams(dp(52),dp(52)));body.addView(h);for(LibraryStore.Item i:library.all(false))if(LibraryStore.TYPE_TIMETABLE.equals(i.type)){TextView row=text(i.title+"   ·   "+i.subject,16,INK);row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setPadding(dp(16),0,dp(16),0);row.setBackground(roundedStroke(Color.WHITE,dp(14),0x12000000));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(62));lp.setMargins(0,dp(7),0,0);body.addView(row,lp);row.setOnClickListener(v->openNotebook(i,false,null));row.setOnLongClickListener(v->{itemActions(i);return true;});}}

    // ---------------- LEARNING / EXAMS ----------------
    private void showStudyHome(){screen="study";closePdf();note=null;root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(22),dp(16),dp(22),dp(14));root.addView(page,new FrameLayout.LayoutParams(-1,-1));LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,INK);back.setOnClickListener(v->showLibrary());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text("Prüfungsvorbereitung",26,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));TextView marks=text("✓ "+study.marks().size(),14,BLUE);marks.setContentDescription("Prüfungsmarkierungen");marks.setOnClickListener(v->showStudyMarks());h.addView(marks,new LinearLayout.LayoutParams(dp(64),dp(52)));TextView plus=text("＋",28,BLUE);plus.setOnClickListener(v->addExamDialog());h.addView(plus,new LinearLayout.LayoutParams(dp(52),dp(52)));page.addView(h);TextView lead=text("Heute",15,MUTED);lead.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);page.addView(lead,new LinearLayout.LayoutParams(-1,dp(44)));ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));ArrayList<StudyStore.Exam> exams=study.exams();if(exams.isEmpty()){TextView e=text("Noch keine Prüfung. Lege ein Datum und Fach fest; der Lernplan verteilt deine Wiederholungen bis zur Prüfung.",16,MUTED);e.setPadding(dp(20),dp(50),dp(20),dp(20));list.addView(e);}for(StudyStore.Exam ex:exams){long days=daysTo(ex.date);int due=study.recommendedToday(ex),mastery=study.masteryPercent(ex.id);LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(12));card.setBackground(roundedStroke(Color.WHITE,dp(15),0x14000000));TextView t=text(ex.title,18,INK);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.LEFT);card.addView(t,new LinearLayout.LayoutParams(-1,dp(32)));TextView m=text(ex.subject+"  ·  "+(days>=0?days+" Tage":"vorbei")+"  ·  "+mastery+" % sicher  ·  heute "+due,13,MUTED);m.setGravity(Gravity.LEFT);card.addView(m,new LinearLayout.LayoutParams(-1,dp(28)));list.addView(card,new LinearLayout.LayoutParams(-1,dp(86)));((LinearLayout.LayoutParams)card.getLayoutParams()).setMargins(0,0,0,dp(9));card.setOnClickListener(v->showExam(ex));}}
    private long daysTo(String date){try{return ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(date));}catch(Exception e){return 0;}}

    private void addExamDialog(){LinearLayout b=formBox();EditText title=new EditText(this);title.setHint("z. B. SHK Klassenarbeit");b.addView(title);Spinner subjects=new Spinner(this);String[] sn=subjectNames();subjects.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sn));b.addView(subjects);final LocalDate[] date={LocalDate.now().plusDays(14)};Button dateB=new Button(this);dateB.setText("Prüfung: "+date[0]);dateB.setOnClickListener(v->{LocalDate q=date[0];new DatePickerDialog(this,(view,y,m,d)->{date[0]=LocalDate.of(y,m+1,d);dateB.setText("Prüfung: "+date[0]);},q.getYear(),q.getMonthValue()-1,q.getDayOfMonth()).show();});b.addView(dateB);new AlertDialog.Builder(this).setTitle("Neue Prüfung").setView(b).setPositiveButton("Anlegen",(d,w)->{StudyStore.Exam e=study.addExam(title.getText().toString(),sn[subjects.getSelectedItemPosition()],date[0].toString(),new ArrayList<>());showExam(e);}).setNegativeButton("Abbrechen",null).show();}

    private void showExam(StudyStore.Exam ex){screen="exam";root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(20),dp(14),dp(20),dp(14));root.addView(p,new FrameLayout.LayoutParams(-1,-1));LinearLayout h=new LinearLayout(this);TextView back=text("‹",32,INK);back.setOnClickListener(v->showStudyHome());h.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));TextView title=text(ex.title,24,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);h.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));TextView more=text("⋯",26,MUTED);more.setOnClickListener(v->examActions(ex));h.addView(more,new LinearLayout.LayoutParams(dp(50),dp(52)));p.addView(h);int cards=study.cardsFor(ex.id).size(),due=study.recommendedToday(ex),mastery=study.masteryPercent(ex.id);TextView status=text(ex.subject+"  ·  "+daysTo(ex.date)+" Tage  ·  "+cards+" Karten  ·  "+mastery+" % sicher",14,MUTED);status.setGravity(Gravity.LEFT);p.addView(status,new LinearLayout.LayoutParams(-1,dp(38)));LinearLayout actions=new LinearLayout(this);Button learn=new Button(this);learn.setText("Heute lernen ("+due+")");learn.setOnClickListener(v->startStudy(ex,false));actions.addView(learn,new LinearLayout.LayoutParams(0,dp(54),1));Button mock=new Button(this);mock.setText("Probeprüfung");mock.setOnClickListener(v->startStudy(ex,true));actions.addView(mock,new LinearLayout.LayoutParams(0,dp(54),1));p.addView(actions);TextView mat=text("Material",15,MUTED);mat.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);p.addView(mat,new LinearLayout.LayoutParams(-1,dp(44)));LinearLayout sourceBox=new LinearLayout(this);sourceBox.setOrientation(LinearLayout.VERTICAL);for(String id:ex.sourceIds){LibraryStore.Item i=library.get(id);if(i!=null){TextView row=text("↗  "+i.title+" · "+i.subject,14,INK);row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setOnClickListener(v->openItem(i));sourceBox.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));}}if(ex.sourceIds.isEmpty()){TextView none=text("Noch kein Material verknüpft.",13,MUTED);none.setGravity(Gravity.LEFT);sourceBox.addView(none,new LinearLayout.LayoutParams(-1,dp(36)));}p.addView(sourceBox);Button sources=new Button(this);sources.setText("Material auswählen");sources.setOnClickListener(v->chooseExamSources(ex));p.addView(sources,new LinearLayout.LayoutParams(-1,dp(48)));TextView cardsTitle=text("Karteikarten",15,MUTED);cardsTitle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);p.addView(cardsTitle,new LinearLayout.LayoutParams(-1,dp(44)));ScrollView sv=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sv.addView(list);p.addView(sv,new LinearLayout.LayoutParams(-1,0,1));for(StudyStore.Card c:study.cardsFor(ex.id)){TextView row=text(c.front+"  →  "+c.back,14,INK);row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(12),0);row.setBackground(rounded(0xaaffffff,dp(10)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.setMargins(0,0,0,dp(5));list.addView(row,lp);row.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("Karte löschen?").setPositiveButton("Löschen",(d,w)->{study.deleteCard(c.id);showExam(ex);}).setNegativeButton("Abbrechen",null).show();return true;});}Button add=new Button(this);add.setText("＋ Karte");add.setOnClickListener(v->addCardDialog(ex));list.addView(add,new LinearLayout.LayoutParams(-1,dp(50)));}

    private void chooseExamSources(StudyStore.Exam ex){ArrayList<LibraryStore.Item> all=new ArrayList<>();for(LibraryStore.Item i:library.all(false))if(!LibraryStore.TYPE_TIMETABLE.equals(i.type))all.add(i);String[] names=new String[all.size()];boolean[] checked=new boolean[all.size()];for(int i=0;i<all.size();i++){names[i]=all.get(i).title+" · "+all.get(i).subject;checked[i]=ex.sourceIds.contains(all.get(i).id);}new AlertDialog.Builder(this).setTitle("Prüfungsstoff").setMultiChoiceItems(names,checked,(d,w,is)->checked[w]=is).setPositiveButton("Speichern",(d,w)->{ex.sourceIds.clear();for(int i=0;i<checked.length;i++)if(checked[i])ex.sourceIds.add(all.get(i).id);study.updateExam(ex);showExam(ex);}).setNegativeButton("Abbrechen",null).show();}
    private void addCardDialog(StudyStore.Exam ex){LinearLayout b=formBox();EditText q=new EditText(this);q.setHint("Frage / Begriff");b.addView(q);EditText a=new EditText(this);a.setHint("Antwort");a.setMinLines(2);if(Build.VERSION.SDK_INT>=34)a.setAutoHandwritingEnabled(true);b.addView(a);new AlertDialog.Builder(this).setTitle("Karteikarte").setView(b).setPositiveButton("Speichern",(d,w)->{if(!q.getText().toString().trim().isEmpty()&&!a.getText().toString().trim().isEmpty())study.addCard(ex.id,q.getText().toString(),a.getText().toString());showExam(ex);}).setNegativeButton("Abbrechen",null).show();}
    private void examActions(StudyStore.Exam ex){String[] a={"Material auswählen","Karte hinzufügen","Karten aus Überschriften/Text erzeugen","Prüfung löschen"};new AlertDialog.Builder(this).setTitle(ex.title).setItems(a,(d,w)->{if(w==0)chooseExamSources(ex);else if(w==1)addCardDialog(ex);else if(w==2)generateCardsFromSources(ex);else new AlertDialog.Builder(this).setTitle("Prüfung wirklich löschen?").setPositiveButton("Löschen",(x,y)->{study.deleteExam(ex.id);showStudyHome();}).setNegativeButton("Abbrechen",null).show();}).show();}


    private void generateCardsFromSources(StudyStore.Exam ex){
        HashSet<String> existing=new HashSet<>();for(StudyStore.Card c:study.cardsFor(ex.id))existing.add(c.front.trim().toLowerCase(Locale.ROOT));int added=0;
        for(String id:ex.sourceIds){LibraryStore.Item item=library.get(id);if(item==null)continue;if(LibraryStore.TYPE_NOTEBOOK.equals(item.type)||LibraryStore.TYPE_WORKSHEET.equals(item.type)){NotebookView n=new NotebookView(this,item.id);for(int p=0;p<n.getPageCount();p++){String title=n.getPageTitle(p).trim();String txt=n.getPlainText(p).trim();String front=!title.isEmpty()?"Erkläre: "+title:(!txt.isEmpty()?"Erkläre den Inhalt von "+item.title+", Seite "+(p+1):"");if(front.isEmpty()||existing.contains(front.toLowerCase(Locale.ROOT)))continue;String back=!txt.isEmpty()?txt:"Öffne "+item.title+", Seite "+(p+1)+", und vergleiche deine Erklärung mit deinen Notizen.";study.addCard(ex.id,front,back);existing.add(front.toLowerCase(Locale.ROOT));added++;}}else if(LibraryStore.TYPE_BOOK.equals(item.type)){String front="Welche Kernpunkte musst du aus „"+item.title+"“ erklären können?";if(!existing.contains(front.toLowerCase(Locale.ROOT))){study.addCard(ex.id,front,"Öffne das verknüpfte Buch und prüfe deine Zusammenfassung anhand der relevanten Seiten.");existing.add(front.toLowerCase(Locale.ROOT));added++;}}}
        toast(added+" Lernkarten erzeugt");showExam(ex);
    }

    private void startStudy(StudyStore.Exam ex,boolean mock){ArrayList<StudyStore.Card> cards=mock?study.cardsFor(ex.id):study.dueFor(ex.id);if(cards.isEmpty())cards=study.cardsFor(ex.id);if(cards.isEmpty()){toast("Lege zuerst Karteikarten an.");return;}if(mock)Collections.shuffle(cards,new Random());showStudyCard(ex,cards,0,mock,0);}
    private void showStudyCard(StudyStore.Exam ex,ArrayList<StudyStore.Card> cards,int index,boolean mock,int score){screen="studycard";if(index>=cards.size()){new AlertDialog.Builder(this).setTitle(mock?"Probeprüfung fertig":"Lerneinheit fertig").setMessage(mock?("Selbsteinschätzung: "+score+" / "+cards.size()):"Wiederholungen wurden neu terminiert.").setPositiveButton("Zur Prüfung",(d,w)->showExam(ex)).setCancelable(false).show();return;}StudyStore.Card card=cards.get(index);root=new FrameLayout(this);root.setBackgroundColor(uiBg());setContentView(root);applySystemBars();LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setGravity(Gravity.CENTER_HORIZONTAL);p.setPadding(dp(28),dp(32),dp(28),dp(24));root.addView(p,new FrameLayout.LayoutParams(-1,-1));TextView top=text((index+1)+" / "+cards.size()+(mock?" · Probeprüfung":" · Wiederholung"),13,MUTED);p.addView(top,new LinearLayout.LayoutParams(-1,dp(38)));TextView q=text(card.front,25,INK);q.setTypeface(Typeface.DEFAULT,Typeface.BOLD);q.setGravity(Gravity.CENTER);q.setBackground(rounded(Color.WHITE,dp(18)));q.setPadding(dp(22),dp(22),dp(22),dp(22));p.addView(q,new LinearLayout.LayoutParams(-1,dp(190)));EditText response=new EditText(this);response.setHint("Antwort aus dem Kopf – tippen oder mit S Pen schreiben");response.setGravity(Gravity.TOP);response.setMinLines(4);if(Build.VERSION.SDK_INT>=34)response.setAutoHandwritingEnabled(true);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(150));rp.setMargins(0,dp(16),0,0);p.addView(response,rp);Button reveal=new Button(this);reveal.setText("Antwort zeigen");p.addView(reveal,new LinearLayout.LayoutParams(-1,dp(56)));LinearLayout answerBox=new LinearLayout(this);answerBox.setOrientation(LinearLayout.VERTICAL);answerBox.setVisibility(View.GONE);TextView ans=text(card.back,20,INK);ans.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);ans.setPadding(dp(18),dp(10),dp(18),dp(10));ans.setBackground(rounded(0xffe8f2e9,dp(14)));answerBox.addView(ans,new LinearLayout.LayoutParams(-1,dp(110)));LinearLayout rates=new LinearLayout(this);String[] labels={"Nochmal","Schwer","Gut","Leicht"};for(int i=0;i<4;i++){Button b=new Button(this);b.setText(labels[i]);final int r=i;b.setOnClickListener(v->{study.rate(card,r,ex);showStudyCard(ex,cards,index+1,mock,score+(r>=2?1:0));});rates.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));}answerBox.addView(rates);p.addView(answerBox);reveal.setOnClickListener(v->{answerBox.setVisibility(View.VISIBLE);reveal.setVisibility(View.GONE);});}

    // ---------------- HANDWRITTEN MATH HINTS ----------------
    private void scheduleMathHint(){
        if(note==null||!prefs.getBoolean("math_hints",true))return;
        if(pendingMathRecognition!=null)ui.removeCallbacks(pendingMathRecognition);
        final NotebookView source=note;final int generation=++mathRecognitionGeneration;
        pendingMathRecognition=()->{
            if(note!=source||generation!=mathRecognitionGeneration)return;

            // Circle-to-calculate has priority: when the user encloses an existing task,
            // recognize every ink stroke inside the loop rather than only the newest line.
            int revision=source.getCurrentInkRevision();
            long circleToken=(((long)source.getCurrentPageIndex())<<32)^(revision&0xffffffffL);
            NotebookView.InkSnapshot circled=source.getCircledMathInkSnapshot();
            if(circled!=null&&circleToken!=lastCircledMathToken){
                lastCircledMathToken=circleToken;
                recognizeMathSnapshot(source,circled,generation,true);
                return;
            }

            NotebookView.InkSnapshot snap=source.getRecentMathInkSnapshot(8500);
            if(snap==null||snap.lastEventTime<=lastMathInkTime)return;
            lastMathInkTime=snap.lastEventTime;
            recognizeMathSnapshot(source,snap,generation,false);
        };
        // Short debounce keeps live feedback responsive while still waiting for parentheses/digits.
        ui.postDelayed(pendingMathRecognition,220);
    }

    private void recognizeMathSnapshot(NotebookView source,NotebookView.InkSnapshot snap,int generation,boolean circled){
        mathRecognizer.recognize(snap,(candidates,error)->{
            if(error!=null||note!=source||generation!=mathRecognitionGeneration)return;
            MathEngine.Result solved=MathEngine.bestCandidate(candidates);
            runOnUiThread(()->{
                if(note!=source||generation!=mathRecognitionGeneration||source.getCurrentPageIndex()!=snap.pageIndex)return;
                if(solved!=null){
                    String value=format(solved.value);lastMathResult=value;
                    showMathHintBubble(source,snap,solved.expression,value,circled);
                }else if(circled){
                    showMathRecognitionFailed(source,snap);
                }else if(!lastMathResult.isEmpty()){hideMathHint();lastMathResult="";}
            });
        });
    }

    private String evaluateRecognizedMath(List<String> candidates){
        MathEngine.Result r=MathEngine.bestCandidate(candidates);return r==null?null:format(r.value);
    }
    private String normalizeMath(String raw){return MathEngine.normalize(raw,true);}

    private void showMathHintBubble(NotebookView source,NotebookView.InkSnapshot snap,String expression,String result,boolean circled){
        String shown=(circled&&expression!=null&&!expression.isEmpty()?prettyMath(expression)+" = ":"= ")+result;
        showMathBubble(source,snap,shown,circled?22000:16000);
    }

    private void showMathRecognitionFailed(NotebookView source,NotebookView.InkSnapshot snap){
        showMathBubble(source,snap,"Rechnung nicht sicher erkannt",6500);
    }

    private String prettyMath(String expression){
        if(expression==null)return"";return expression.replace("*","×").replace("/",":");
    }

    private void showMathBubble(NotebookView source,NotebookView.InkSnapshot snap,String textValue,long duration){
        hideMathHint();if(root==null||source==null)return;
        float[] local=source.pageToLocal(Math.min(NotebookView.PAGE_W-30,snap.maxX+26),Math.max(30,snap.centerY()));
        int[] nl=new int[2],rl=new int[2];source.getLocationOnScreen(nl);root.getLocationOnScreen(rl);
        int w=Math.min(dp(300),Math.max(dp(112),dp(22)+textValue.length()*dp(8)));int h=dp(48);
        int x=Math.round(nl[0]-rl[0]+local[0]);int y=Math.round(nl[1]-rl[1]+local[1]-h/2f);
        x=Math.max(dp(6),Math.min(root.getWidth()-w-dp(6),x));y=Math.max(dp(6),Math.min(root.getHeight()-h-dp(6),y));
        TextView bubble=new SpeechBubble(this);bubble.setText(textValue);bubble.setTextSize(textValue.length()>24?14:17);bubble.setTextColor(0xff16324a);bubble.setTypeface(Typeface.DEFAULT,Typeface.BOLD);bubble.setGravity(Gravity.CENTER);bubble.setElevation(dp(16));bubble.setPadding(dp(10),0,dp(10),dp(5));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h);lp.leftMargin=x;lp.topMargin=y;root.addView(bubble,lp);mathHintBubble=bubble;bubble.setAlpha(0);bubble.setScaleX(.86f);bubble.setScaleY(.86f);bubble.animate().alpha(1).scaleX(1).scaleY(1).setDuration(150).start();bubble.setOnClickListener(v->hideMathHint());ui.postDelayed(()->{if(mathHintBubble==bubble)hideMathHint();},duration);
    }
    private void hideMathHint(){if(mathHintBubble!=null&&mathHintBubble.getParent()==root)root.removeView(mathHintBubble);mathHintBubble=null;}

    private class SpeechBubble extends TextView {
        private final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tail=new Path();
        SpeechBubble(Context c){super(c);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas c){
            float tailH=dp(7),r=dp(16);
            bg.setStyle(Paint.Style.FILL);bg.setColor(0xffeaf6ff);
            c.drawRoundRect(0,0,getWidth(),getHeight()-tailH,r,r,bg);
            tail.reset();tail.moveTo(dp(18),getHeight()-tailH);tail.lineTo(dp(29),getHeight()-tailH);tail.lineTo(dp(22),getHeight());tail.close();c.drawPath(tail,bg);
            bg.setStyle(Paint.Style.STROKE);bg.setStrokeWidth(dp(1));bg.setColor(0x553157d5);
            c.drawRoundRect(.5f,.5f,getWidth()-.5f,getHeight()-tailH-.5f,r,r,bg);
            super.onDraw(c);
        }
    }

    // ---------------- EXPORT / CALCULATOR ----------------
    private void exportCurrentPdf(){if(note==null||currentItem==null)return;try{pendingExport=new File(getCacheDir(),safeName(currentItem.title)+".pdf");note.exportPdf(pendingExport);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/pdf");i.putExtra(Intent.EXTRA_TITLE,safeName(currentItem.title)+".pdf");startActivityForResult(i,REQ_EXPORT);}catch(Exception e){toast("PDF konnte nicht erstellt werden");}}
    private String safeName(String s){s=s==null?"Schulranzen":s.trim();return s.replaceAll("[\\\\/:*?\"<>|]","_");}

    private void showCalculator(){
        if(root==null)return;if(transientToolPanel!=null)closeToolPanel();if(calculatorWindow!=null&&calculatorWindow.getParent()==root){closeCalculator();return;}closeCalculator();
        calculatorWindow=new FrameLayout(this);calculatorWindow.setBackground(roundedStroke(darkMode()?0xff1f2228:0xfffbfbfa,dp(22),darkMode()?0x443b414b:0x22000000));calculatorWindow.setElevation(dp(24));
        int screenW=Math.max(dp(320),getResources().getDisplayMetrics().widthPixels),screenH=Math.max(dp(480),getResources().getDisplayMetrics().heightPixels);int w=Math.min(dp(390),screenW-dp(20)),h=Math.max(dp(350),Math.min(dp(525),screenH-dp(90)));FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(w,h);wp.leftMargin=dp(12);wp.topMargin=dp(64);root.addView(calculatorWindow,wp);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(8),dp(14),dp(14));calculatorWindow.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Rechner",15,uiInk());title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);head.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));TextView close=text("×",26,uiMuted());close.setContentDescription("Taschenrechner schließen");close.setOnClickListener(v->closeCalculator());head.addView(close,new LinearLayout.LayoutParams(dp(46),dp(40)));panel.addView(head);
        final float[] drag={0,0,0,0};head.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){drag[0]=e.getRawX();drag[1]=e.getRawY();drag[2]=calculatorWindow.getX();drag[3]=calculatorWindow.getY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float nx=drag[2]+e.getRawX()-drag[0],ny=drag[3]+e.getRawY()-drag[1];float maxX=Math.max(0,root.getWidth()-calculatorWindow.getWidth()),maxY=Math.max(0,root.getHeight()-calculatorWindow.getHeight());calculatorWindow.setX(Math.max(0,Math.min(maxX,nx)));calculatorWindow.setY(Math.max(0,Math.min(maxY,ny)));return true;}return e.getActionMasked()==MotionEvent.ACTION_UP;});
        EditText expr=new EditText(this);expr.setTextSize(23);expr.setTextColor(uiInk());expr.setHintTextColor(uiMuted());expr.setHint("(12+8)×3 − √(16)");expr.setSingleLine(true);expr.setImeOptions(EditorInfo.IME_ACTION_DONE);expr.setBackgroundColor(Color.TRANSPARENT);expr.setPadding(dp(6),0,dp(6),0);panel.addView(expr,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView result=text("= 0",28,darkMode()?0xff8ec5ff:0xff3157d5);result.setTypeface(Typeface.DEFAULT,Typeface.BOLD);result.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);result.setPadding(dp(6),0,dp(6),0);panel.addView(result,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView hint=text("Live-Ergebnis · Klammern, Potenzen, √, sin/cos/tan, ln/log",11,uiMuted());hint.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);panel.addView(hint,new LinearLayout.LayoutParams(-1,dp(30)));
        GridLayout g=new GridLayout(this);g.setColumnCount(5);String[] keys={"C","⌫","(",")","÷","7","8","9","√","×","4","5","6","^","-","1","2","3","%","+","0",".","π","ANS","=","sin","cos","tan","ln","log"};final double[] ans={calculatorAns};for(String k:keys){TextView key=text(k,15,k.equals("=")?Color.WHITE:uiInk());boolean eq=k.equals("=");boolean op="÷×-+^√%".contains(k)||k.equals("sin")||k.equals("cos")||k.equals("tan")||k.equals("ln")||k.equals("log");key.setBackground(rounded(eq?0xff3157d5:op?(darkMode()?0xff333944:0xffeceef2):(darkMode()?0xff292d34:0xffffffff),dp(13)));key.setElevation(dp(1));GridLayout.LayoutParams q=new GridLayout.LayoutParams();q.width=(w-dp(40))/5;q.height=dp(54);q.setMargins(dp(2),dp(2),dp(2),dp(2));g.addView(key,q);key.setOnClickListener(v->{String x=((TextView)v).getText().toString();if(x.equals("C")){expr.setText("");result.setText("= 0");}else if(x.equals("⌫")){int n=expr.length();if(n>0)expr.getText().delete(n-1,n);}else if(x.equals("=")){compute(expr,result,ans);}else if(x.equals("ANS"))insertAtCursor(expr,format(ans[0]));else if(x.equals("×"))insertAtCursor(expr,"*");else if(x.equals("÷"))insertAtCursor(expr,"/");else if(x.equals("√"))insertAtCursor(expr,"sqrt(");else if(x.equals("π"))insertAtCursor(expr,"pi");else if(x.equals("sin")||x.equals("cos")||x.equals("tan")||x.equals("ln")||x.equals("log"))insertAtCursor(expr,x+"(");else insertAtCursor(expr,x);});}panel.addView(g,new LinearLayout.LayoutParams(-1,0,1));
        // Resize handle: calculator remains a floating window, but can be resized for portrait,
        // landscape or split-screen. Key sizes are recomputed from the actual window size.
        TextView resize=text("↘",18,uiMuted());resize.setGravity(Gravity.CENTER);resize.setContentDescription("Taschenrechner skalieren");
        FrameLayout.LayoutParams rh=new FrameLayout.LayoutParams(dp(38),dp(38),Gravity.RIGHT|Gravity.BOTTOM);calculatorWindow.addView(resize,rh);
        final float[] rs={0,0,0,0};resize.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){rs[0]=e.getRawX();rs[1]=e.getRawY();rs[2]=calculatorWindow.getWidth();rs[3]=calculatorWindow.getHeight();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){int nw=Math.round(rs[2]+e.getRawX()-rs[0]),nh=Math.round(rs[3]+e.getRawY()-rs[1]);int maxW=Math.max(dp(280),root.getWidth()-Math.round(calculatorWindow.getX())-dp(6)),maxH=Math.max(dp(330),root.getHeight()-Math.round(calculatorWindow.getY())-dp(6));nw=Math.max(dp(280),Math.min(maxW,nw));nh=Math.max(dp(330),Math.min(maxH,nh));FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)calculatorWindow.getLayoutParams();lp.width=nw;lp.height=nh;calculatorWindow.setLayoutParams(lp);return true;}
            return e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL;
        });
        calculatorWindow.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{
            int inner=Math.max(dp(220),calculatorWindow.getWidth()-dp(28));int cellW=Math.max(dp(42),(inner-dp(20))/5);
            int availableH=Math.max(dp(180),calculatorWindow.getHeight()-dp(40+56+52+30+28));int cellH=Math.max(dp(30),Math.min(dp(58),(availableH-dp(18))/6));
            for(int i=0;i<g.getChildCount();i++){View key=g.getChildAt(i);ViewGroup.LayoutParams base=key.getLayoutParams();if(base instanceof GridLayout.LayoutParams){GridLayout.LayoutParams q=(GridLayout.LayoutParams)base;q.width=cellW;q.height=cellH;key.setLayoutParams(q);}}
            expr.setTextSize(calculatorWindow.getWidth()<dp(330)?19:23);result.setTextSize(calculatorWindow.getWidth()<dp(330)?23:28);
        });

        expr.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){String raw=s.toString().trim();if(raw.isEmpty()){result.setText("= 0");return;}try{double v=new CalcParser(raw).parse();if(Double.isFinite(v)){result.setText("= "+format(v));calculatorAns=v;ans[0]=v;}else result.setText("= …");}catch(Exception ex){result.setText("= …");}}public void afterTextChanged(Editable e){}});
        expr.setOnEditorActionListener((v,a,e)->{compute(expr,result,ans);return true;});
        calculatorWindow.post(()->{if(calculatorWindow==null||calculatorWindow.getParent()!=root)return;calculatorWindow.setX(Math.max(dp(8),(root.getWidth()-calculatorWindow.getWidth())*.5f));calculatorWindow.setY(Math.max(dp(48),Math.min(root.getHeight()-calculatorWindow.getHeight()-dp(8),dp(70))));});
    }
    private void insertAtCursor(EditText e,String s){int a=Math.max(0,e.getSelectionStart()),b=Math.max(0,e.getSelectionEnd());int lo=Math.min(a,b),hi=Math.max(a,b);e.getText().replace(lo,hi,s);e.setSelection(Math.min(e.length(),lo+s.length()));}

    private void closeCalculator(){if(calculatorWindow!=null&&calculatorWindow.getParent()==root)root.removeView(calculatorWindow);calculatorWindow=null;}

    private void compute(EditText e,TextView r,double[] ans){try{double v=new CalcParser(e.getText().toString()).parse();ans[0]=v;calculatorAns=v;r.setText("= "+format(v));}catch(Exception ex){r.setText("= Fehler");}}
    private String format(double v){return Math.rint(v)==v?Long.toString((long)v):String.format(Locale.GERMANY,"%.6f",v).replaceAll("0+$","").replaceAll(",?$","");}
    private static class CalcParser {
        final String raw;CalcParser(String raw){this.raw=raw;}double parse(){return MathEngine.evaluate(raw);}
    }

    // ---------------- SETTINGS / SERVER / TRASH ----------------
    private void settings(){String[] a={"Server & Vollbackup","Fächer verwalten","Darstellung (Hell / Dunkel)","Stiftdruck & Neigung","Rechenhinweise (Handschrift)","Papierkorb","Eigene Vorlage zurücksetzen"};new AlertDialog.Builder(this).setTitle("Einstellungen").setItems(a,(d,w)->{if(w==0)serverSettings();else if(w==1)subjectsSettings();else if(w==2)themeSettings();else if(w==3)pressureSettings();else if(w==4)mathHintSettings();else if(w==5)trashScreen();else{prefs.edit().remove("template_paper").remove("template_bg").putBoolean("template_default",false).apply();toast("Vorlage zurückgesetzt");}}).show();}
    private void themeSettings(){String mode=prefs.getString("theme_mode","auto");String[] a={"Automatisch (Android)","Hell","Dunkel"};int checked="light".equals(mode)?1:"dark".equals(mode)?2:0;new AlertDialog.Builder(this).setTitle("Darstellung").setSingleChoiceItems(a,checked,(d,w)->{prefs.edit().putString("theme_mode",w==1?"light":w==2?"dark":"auto").apply();d.dismiss();applySystemBars();showLibrary();}).show();}
    private void pressureSettings(){int mode=prefs.getInt("pressure_mode",2);boolean tilt=prefs.getBoolean("tilt_enabled",true);String[] a={"Druck aus","Weich","Natürlich","Stark"};LinearLayout box=formBox();TextView info=text("Stiftdruck",14,INK);info.setGravity(Gravity.LEFT);box.addView(info,new LinearLayout.LayoutParams(-1,dp(32)));Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,a));sp.setSelection(mode);box.addView(sp);android.widget.CheckBox cb=new android.widget.CheckBox(this);cb.setText("Stiftneigung verwenden");cb.setChecked(tilt);box.addView(cb);new AlertDialog.Builder(this).setTitle("S Pen").setView(box).setPositiveButton("Speichern",(d,w)->prefs.edit().putInt("pressure_mode",sp.getSelectedItemPosition()).putBoolean("tilt_enabled",cb.isChecked()).apply()).setNegativeButton("Abbrechen",null).show();}
    private void mathHintSettings(){boolean on=prefs.getBoolean("math_hints",true);boolean[] checked={on};new AlertDialog.Builder(this).setTitle("Rechenhinweise").setMultiChoiceItems(new String[]{"Handgeschriebene Rechenaufgaben erkennen und Ergebnis anzeigen"},checked,(d,w,is)->checked[0]=is).setPositiveButton("Speichern",(d,w)->{prefs.edit().putBoolean("math_hints",checked[0]).apply();if(checked[0])mathRecognizer.prepare((x,e)->{if(e!=null)runOnUiThread(()->toast("Erkennungsmodell konnte noch nicht geladen werden"));});else{if(pendingMathRecognition!=null)ui.removeCallbacks(pendingMathRecognition);hideMathHint();}}).setNegativeButton("Abbrechen",null).show();}
    private void serverSettings(){LinearLayout box=formBox();EditText server=new EditText(this);server.setHint("Server-IP oder URL, z. B. 192.168.2.5:8788");server.setText(prefs.getString("server",""));box.addView(server);EditText mail=new EditText(this);mail.setHint("E-Mail");mail.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);mail.setText(prefs.getString("mail",""));box.addView(mail);EditText pin=new EditText(this);pin.setHint("6-stellige PIN");pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);box.addView(pin);TextView status=text(prefs.contains("token")?"Angemeldet · offline-first":"Nicht angemeldet",13,MUTED);status.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);box.addView(status,new LinearLayout.LayoutParams(-1,dp(40)));Button test=new Button(this);test.setText("Verbindung testen");box.addView(test);LinearLayout auth=new LinearLayout(this);Button reg=new Button(this);reg.setText("Registrieren");Button login=new Button(this);login.setText("Anmelden");auth.addView(reg,new LinearLayout.LayoutParams(0,dp(50),1));auth.addView(login,new LinearLayout.LayoutParams(0,dp(50),1));box.addView(auth);LinearLayout sync=new LinearLayout(this);Button up=new Button(this);up.setText("Jetzt sichern");Button down=new Button(this);down.setText("Letztes Backup laden");sync.addView(up,new LinearLayout.LayoutParams(0,dp(50),1));sync.addView(down,new LinearLayout.LayoutParams(0,dp(50),1));box.addView(sync);AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Unraid-Synchronisierung").setView(box).setPositiveButton("Speichern",(d,w)->saveCredentials(server,mail)).setNegativeButton("Schließen",null).create();test.setOnClickListener(v->{saveCredentials(server,mail);serverRequest("health",server.getText().toString(),mail.getText().toString(),pin.getText().toString(),status);});reg.setOnClickListener(v->{if(validPin(pin)){saveCredentials(server,mail);serverRequest("register",server.getText().toString(),mail.getText().toString(),pin.getText().toString(),status);}});login.setOnClickListener(v->{if(validPin(pin)){saveCredentials(server,mail);serverRequest("login",server.getText().toString(),mail.getText().toString(),pin.getText().toString(),status);}});up.setOnClickListener(v->{saveCredentials(server,mail);if(note!=null)note.flushSave();if(bookAnnotations!=null)bookAnnotations.flushSave();status.setText("Sicherung läuft …");backup.uploadAsync((ok,msg)->status.setText(msg));});down.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Server-Backup wiederherstellen?").setMessage("Lokale Daten werden durch den letzten Serverstand ersetzt.").setPositiveButton("Wiederherstellen",(x,y)->{status.setText("Wiederherstellung läuft …");backup.restoreLatestAsync((ok,msg)->status.setText(msg));}).setNegativeButton("Abbrechen",null).show());dlg.show();}
    private void saveCredentials(EditText s,EditText m){prefs.edit().putString("server",s.getText().toString().trim()).putString("mail",m.getText().toString().trim()).apply();}
    private boolean validPin(EditText p){if(!p.getText().toString().matches("\\d{6}")){p.setError("Genau 6 Ziffern");return false;}return true;}
    private String normalizeServer(String s){s=s.trim();if(!s.startsWith("http://")&&!s.startsWith("https://"))s="http://"+s;while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}
    private void serverRequest(String mode,String server,String mail,String pin,TextView status){status.setText("Verbindung läuft …");new Thread(()->{HttpURLConnection c=null;try{String base=normalizeServer(server);String ep=mode.equals("health")?"/health":mode.equals("register")?"/api/register":"/api/login";c=(HttpURLConnection)new URL(base+ep).openConnection();c.setConnectTimeout(4500);c.setReadTimeout(6000);c.setRequestProperty("Accept","application/json");if(!mode.equals("health")){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");String body="{\"email\":\""+json(mail.trim())+"\",\"pin\":\""+json(pin)+"\"}";try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String resp=readAll(in);if(code>=200&&code<300){Matcher m=Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(resp);if(m.find())prefs.edit().putString("token",m.group(1)).apply();runOnUiThread(()->status.setText(mode.equals("health")?"Server erreichbar":"Erfolgreich · angemeldet"));}else runOnUiThread(()->status.setText("Serverfehler "+code));}catch(Exception e){runOnUiThread(()->status.setText("Keine Verbindung: "+e.getClass().getSimpleName()));}finally{if(c!=null)c.disconnect();}}).start();}
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
    private static String readAll(InputStream in)throws Exception{if(in==null)return"";BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String l;while((l=r.readLine())!=null)b.append(l);return b.toString();}

    private void subjectsSettings(){ArrayList<LibraryStore.Subject>s=library.subjects();String[] n=new String[s.size()+1];for(int i=0;i<s.size();i++)n[i]=s.get(i).name;n[s.size()]="＋ Neues Fach";new AlertDialog.Builder(this).setTitle("Fächer").setItems(n,(d,w)->{if(w==s.size())newSubjectDialog();else editSubjectDialog(s.get(w));}).show();}
    private void newSubjectDialog(){EditText e=new EditText(this);e.setHint("Fachname");new AlertDialog.Builder(this).setTitle("Neues Fach").setView(e).setPositiveButton("Speichern",(d,w)->{String name=e.getText().toString().trim();if(!name.isEmpty())library.addOrUpdateSubject(name,0xff3157d5);showLibrary();}).setNegativeButton("Abbrechen",null).show();}
    private void editSubjectDialog(LibraryStore.Subject s){String[] a={"Umbenennen / Farbe","Fach löschen"};new AlertDialog.Builder(this).setTitle(s.name).setItems(a,(d,w)->{if(w==0){LinearLayout box=formBox();EditText e=new EditText(this);e.setText(s.name);box.addView(e);String[] colors={"Blau","Grün","Rot","Orange","Violett","Petrol","Anthrazit"};int[] vals={0xff3157d5,0xff16875b,0xffd84b5b,0xffd38324,0xff7b4bc4,0xff3d6b7d,0xff59544d};Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,colors));box.addView(sp);new AlertDialog.Builder(this).setTitle("Fach bearbeiten").setView(box).setPositiveButton("Speichern",(x,y)->{library.renameSubject(s.name,e.getText().toString(),vals[sp.getSelectedItemPosition()]);showLibrary();}).setNegativeButton("Abbrechen",null).show();}else{library.removeSubject(s.name);showLibrary();}}).show();}
    private void trashScreen(){ArrayList<LibraryStore.Item>a=library.all(true);a.removeIf(i->!i.deleted);if(a.isEmpty()){toast("Papierkorb ist leer");return;}String[] n=new String[a.size()];for(int i=0;i<n.length;i++)n[i]=a.get(i).title+" · "+a.get(i).subject;new AlertDialog.Builder(this).setTitle("Papierkorb").setItems(n,(d,w)->{LibraryStore.Item i=a.get(w);new AlertDialog.Builder(this).setTitle(i.title).setItems(new String[]{"Wiederherstellen","Endgültig löschen"},(x,y)->{if(y==0)library.restore(i.id);else library.purge(i.id);showLibrary();}).show();}).show();}

    // ---------------- HELPERS / LIFECYCLE ----------------
    private void cancelTransientUi(){overviewGeneration++;clearOverviewBitmaps();hideHoverLabel();hideMathHint();closeCalculator();closeToolPanel();if(pendingMathRecognition!=null)ui.removeCallbacks(pendingMathRecognition);pendingMathRecognition=null;lastMathInkTime=0;lastCircledMathToken=-1L;}
    private void closePdf(){if(bookAnnotations!=null)bookAnnotations.flushSave();if(pdf!=null){pdf.close();pdf=null;}bookInk=null;bookAnnotations=null;bookBubble=null;}
    private static void copyFile(File a,File b)throws Exception{if(!a.exists())return;try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){copyStream(in,out);}}
    private static void copyStream(InputStream in,OutputStream out)throws Exception{byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}

    @Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);applySystemBars();closeToolPanel();if(activeToolDock!=null&&activeToolDock.getParent()==root)root.removeView(activeToolDock);activeToolDock=null;if(note!=null&&currentItem!=null){addNotebookToolDock(currentItem.color);updateNotebookDock();if(pageInfo!=null){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)pageInfo.getLayoutParams();lp.topMargin=newConfig.orientation==Configuration.ORIENTATION_LANDSCAPE?dp(7):dp(67);pageInfo.setLayoutParams(lp);}}else if(bookInk!=null&&currentItem!=null){addBookToolDock(currentItem.color);updateBookDock();}else if("library".equals(screen)){showLibrary();}}

    @Override protected void onPause(){super.onPause();if(note!=null)note.flushSave();if(bookAnnotations!=null)bookAnnotations.flushSave();if(dirty){dirty=false;long last=prefs.getLong("last_auto_sync",0);boolean auto=prefs.contains("token")&&System.currentTimeMillis()-last>15*60*1000L;if(auto){prefs.edit().putLong("last_auto_sync",System.currentTimeMillis()).apply();backup.uploadAsync((ok,msg)->{});}else new Thread(()->{try{backup.createLocalBackup();}catch(Exception ignored){}}).start();}}

    @Override public void onBackPressed(){if(transientToolPanel!=null){closeToolPanel();return;}if(calculatorWindow!=null&&calculatorWindow.getParent()==root){closeCalculator();return;}if(overviewOverlay!=null){closeOverview();return;}if(!radialViews.isEmpty()){closeRadial();return;}if(note!=null){note.flushSave();if(currentItem!=null&&LibraryStore.TYPE_TIMETABLE.equals(currentItem.type))showTimetables();else showLibrary();return;}if(pdf!=null){showLibrary();return;}if("exam".equals(screen)||"studycard".equals(screen)){showStudyHome();return;}if("shkcalc".equals(screen)||"shkunits".equals(screen)||"shkformula".equals(screen)){showShkHome();return;}if("studymarks".equals(screen)){showStudyHome();return;}if("study".equals(screen)||"shk".equals(screen)||"timetables".equals(screen)||"search".equals(screen)){showLibrary();return;}super.onBackPressed();}
    @Override protected void onDestroy(){super.onDestroy();if(mathRecognizer!=null)mathRecognizer.close();}
}
