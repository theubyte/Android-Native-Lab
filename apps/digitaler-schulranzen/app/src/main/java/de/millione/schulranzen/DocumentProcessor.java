package de.millione.schulranzen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

/** Small dependency-free document cleanup: detects a bright paper quadrilateral and deskews it to A4. */
public final class DocumentProcessor {
    private DocumentProcessor(){}

    public static Bitmap toA4(Bitmap src,int tw,int th){
        if(src==null) return null;
        float factor=Math.min(1f,1200f/Math.max(src.getWidth(),src.getHeight()));
        Bitmap scan=factor<.999f?Bitmap.createScaledBitmap(src,Math.max(2,(int)(src.getWidth()*factor)),Math.max(2,(int)(src.getHeight()*factor)),true):src;
        float[] quad=findPaper(scan);
        Bitmap out=Bitmap.createBitmap(tw,th,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.WHITE);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        if(quad!=null){
            for(int i=0;i<8;i++)quad[i]/=factor;
            float top=dist(quad[0],quad[1],quad[2],quad[3]), bottom=dist(quad[6],quad[7],quad[4],quad[5]);
            float left=dist(quad[0],quad[1],quad[6],quad[7]), right=dist(quad[2],quad[3],quad[4],quad[5]);
            boolean landscape=(top+bottom)>(left+right)*1.12f;
            float[] dst=landscape?new float[]{tw,0,tw,th,0,th,0,0}:new float[]{0,0,tw,0,tw,th,0,th};
            Matrix m=new Matrix();
            if(m.setPolyToPoly(quad,0,dst,0,4)){c.drawBitmap(src,m,p);}
            else fit(src,c,p,tw,th);
        } else fit(src,c,p,tw,th);
        if(scan!=src)scan.recycle();
        return out;
    }

    private static void fit(Bitmap src,Canvas c,Paint p,int tw,int th){float s=Math.min((float)tw/src.getWidth(),(float)th/src.getHeight());float w=src.getWidth()*s,h=src.getHeight()*s;c.drawBitmap(src,null,new RectF((tw-w)/2f,(th-h)/2f,(tw+w)/2f,(th+h)/2f),p);}

    // returns TL,TR,BR,BL in scan coordinates
    private static float[] findPaper(Bitmap b){
        int w=b.getWidth(),h=b.getHeight(),step=Math.max(2,Math.min(w,h)/360);long mask=0,total=0;
        float tlx=0,tly=0,trx=0,tryy=0,brx=0,bry=0,blx=0,bly=0;float tl=Float.MAX_VALUE,tr=Float.MAX_VALUE,br=-Float.MAX_VALUE,bl=-Float.MAX_VALUE;
        for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){total++;int c=b.getPixel(x,y);int r=Color.red(c),g=Color.green(c),bb=Color.blue(c);int max=Math.max(r,Math.max(g,bb)),min=Math.min(r,Math.min(g,bb));int lum=(r*3+g*6+bb)/10;if(lum<145||max-min>95)continue;mask++;float stl=x+y,str=(w-x)+y,sbr=x+y,sbl=(w-x)+y;if(stl<tl){tl=stl;tlx=x;tly=y;}if(str<tr){tr=str;trx=x;tryy=y;}if(sbr>br){br=sbr;brx=x;bry=y;}if(sbl>bl){bl=sbl;blx=x;bly=y;}}
        float coverage=total==0?0:(float)mask/total;if(coverage<.08f||coverage>.96f)return null;
        float top=dist(tlx,tly,trx,tryy),bottom=dist(blx,bly,brx,bry),left=dist(tlx,tly,blx,bly),right=dist(trx,tryy,brx,bry);if(Math.min(top,bottom)<w*.25f||Math.min(left,right)<h*.25f)return null;
        return new float[]{tlx,tly,trx,tryy,brx,bry,blx,bly};
    }
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
}
