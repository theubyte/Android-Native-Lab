package de.millione.schulranzen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Memory-conscious PDF reader with finger-centred pinch zoom, panning, page swipes,
 * page jump hook and five-finger overview hook. Only the visible page is rendered.
 */
public class PdfPane extends FrameLayout {
    public interface OnOverviewListener { void overviewRequested(); }
    public interface OnNavigatorListener { void navigatorRequested(); }
    public interface OnPageChangedListener { void pageChanged(int pageIndex, int pageCount); }
    public interface OnTransformListener { void transformChanged(); }

    private final ImageView image;
    private final TextView badge;
    private final SharedPreferences prefs;
    private final Matrix matrix = new Matrix();
    private final Object renderLock = new Object();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private OnOverviewListener overviewListener;
    private OnNavigatorListener navigatorListener;
    private OnPageChangedListener pageChangedListener;
    private OnTransformListener transformListener;
    private int page = 0;
    private final String uriString;
    private Bitmap currentBitmap;

    private float minScale = 1f, currentScale = 1f, maxScale = 5f;
    private float downX, downY, lastX, lastY;
    private boolean panning = false;
    private boolean multiPanning = false;
    private float multiLastFocusX = 0f, multiLastFocusY = 0f;
    private boolean stylusNear = false;
    private boolean fiveFingerActive = false;
    private boolean fiveFingerTriggered = false;
    private float fiveStartSpan = 0f;

    public PdfPane(Context c, String uri) {
        super(c);
        uriString = uri;
        prefs = c.getSharedPreferences("bag", 0);
        setBackgroundColor(0xff20242b);
        setClipChildren(true);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setBackgroundColor(0xff20242b);
        addView(image, new FrameLayout.LayoutParams(-1, -1));

        badge = new TextView(c);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(16, 8, 16, 8);
        badge.setBackgroundColor(0xb0000000);
        badge.setContentDescription("Seite wählen");
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        bp.setMargins(0, 0, 14, 14);
        addView(badge, bp);
        badge.setOnClickListener(v -> { if (navigatorListener != null) navigatorListener.navigatorRequested(); });
        badge.setOnLongClickListener(v -> { if (overviewListener != null) overviewListener.overviewRequested(); return true; });

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (currentBitmap == null) return false;
                float target = clamp(currentScale * detector.getScaleFactor(), minScale, maxScale);
                float factor = target / Math.max(0.0001f, currentScale);
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                currentScale = target;
                clampMatrix();
                applyMatrix();
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentBitmap == null) return true;
                if (currentScale <= minScale * 1.08f) {
                    float target = Math.min(maxScale, minScale * 2.15f);
                    float factor = target / currentScale;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    currentScale = target;
                    clampMatrix();
                    applyMatrix();
                } else resetZoom();
                return true;
            }
        });

        setOnHoverListener((v, e) -> {
            int type = e.getToolType(Math.max(0, Math.min(e.getActionIndex(), e.getPointerCount() - 1)));
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                if (e.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER || e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) stylusNear = true;
                if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) stylusNear = false;
                return true;
            }
            return false;
        });
        setOnTouchListener((v, e) -> handleTouch(e));

        try {
            if (uri != null) {
                descriptor = AppFileResolver.openPfd(c, uri);
                renderer = new PdfRenderer(descriptor);
                if (renderer.getPageCount() > 0) {
                    page = Math.max(0, Math.min(prefs.getInt(pageKey(), 0), renderer.getPageCount() - 1));
                    showPage();
                } else badge.setText("Leeres PDF");
            }
        } catch (Exception e) {
            badge.setText("PDF konnte nicht geöffnet werden");
        }
    }

    public void setOverviewListener(OnOverviewListener l) { overviewListener = l; }
    public void setNavigatorListener(OnNavigatorListener l) { navigatorListener = l; }
    public void setPageChangedListener(OnPageChangedListener l) { pageChangedListener = l; notifyPage(); }
    public void setTransformListener(OnTransformListener l) { transformListener = l; }
    public int getPageCount() { return renderer == null ? 0 : renderer.getPageCount(); }
    public int getCurrentPage() { return page; }
    public String getUriString() { return uriString; }
    public float getZoomFactor() { return minScale <= 0 ? 1f : currentScale / minScale; }

    public int getCurrentRotation() { return pageRotation(page); }

    /** Rotate only the current PDF page clockwise. Rotation is persisted per book/page. */
    public void rotateCurrentPageClockwise() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        int next = (pageRotation(page) + 90) % 360;
        prefs.edit().putInt(rotationKey(page), next).apply();
        showPage();
    }

    /** Reset the current page rotation to the original PDF orientation. */
    public void resetCurrentPageRotation() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        prefs.edit().remove(rotationKey(page)).apply();
        showPage();
    }

    public void goToPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;
        page = index;
        showPage();
    }

    public void resetZoom() {
        if (currentBitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
        float sx = getWidth() / (float) currentBitmap.getWidth();
        float sy = getHeight() / (float) currentBitmap.getHeight();
        minScale = Math.min(sx, sy);
        currentScale = minScale;
        maxScale = minScale * 10.0f;
        float tx = (getWidth() - currentBitmap.getWidth() * minScale) * 0.5f;
        float ty = (getHeight() - currentBitmap.getHeight() * minScale) * 0.5f;
        matrix.reset();
        matrix.postScale(minScale, minScale);
        matrix.postTranslate(tx, ty);
        applyMatrix();
        updateBadge();
    }

    private boolean handleTouch(MotionEvent e) {
        int ai = Math.max(0, Math.min(e.getActionIndex(), e.getPointerCount() - 1));
        int type = e.getToolType(ai);
        boolean stylus = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
        int action = e.getActionMasked();
        if (stylus || stylusNear) return true;

        if (e.getPointerCount() >= 5) {
            float span = multiSpan(e);
            if (!fiveFingerActive) {
                fiveFingerActive = true;
                fiveFingerTriggered = false;
                fiveStartSpan = span;
            } else if (!fiveFingerTriggered && fiveStartSpan > 0 && span < fiveStartSpan * .70f) {
                fiveFingerTriggered = true;
                if (overviewListener != null) overviewListener.overviewRequested();
            }
            return true;
        }
        if (fiveFingerActive && e.getPointerCount() < 5) {
            fiveFingerActive = false;
            fiveStartSpan = 0f;
        }

        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);

        // Two fingers can always move the page, even at fit zoom. This is independent
        // from pinch scaling so the PDF can be positioned freely instead of being
        // forced back to the centre of the viewport.
        if (e.getPointerCount() >= 2) {
            float fx = 0f, fy = 0f; int n = Math.min(2, e.getPointerCount());
            for (int i = 0; i < n; i++) { fx += e.getX(i); fy += e.getY(i); }
            fx /= n; fy /= n;
            if (!multiPanning || action == MotionEvent.ACTION_POINTER_DOWN) {
                multiPanning = true; multiLastFocusX = fx; multiLastFocusY = fy;
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = fx - multiLastFocusX, dy = fy - multiLastFocusY;
                if (Math.abs(dx) > .05f || Math.abs(dy) > .05f) {
                    matrix.postTranslate(dx, dy);
                    multiLastFocusX = fx; multiLastFocusY = fy;
                    clampMatrix(); applyMatrix();
                }
            }
            panning = false;
            return true;
        } else if (multiPanning) {
            multiPanning = false;
        }
        if (scaleDetector.isInProgress()) { panning = false; return true; }

        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = e.getX();
            downY = lastY = e.getY();
            panning = currentScale > minScale * 1.02f;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && panning) {
            float dx = e.getX() - lastX, dy = e.getY() - lastY;
            matrix.postTranslate(dx, dy);
            lastX = e.getX(); lastY = e.getY();
            clampMatrix();
            applyMatrix();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (currentScale <= minScale * 1.02f && Math.abs(dx) > 90 && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                if (dx < 0) next(); else prev();
            }
            panning = false;
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            panning = false;
            multiPanning = false;
            fiveFingerActive = false;
            return true;
        }
        return true;
    }

    private void showPage() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        try {
            Bitmap b;
            synchronized (renderLock) {
                PdfRenderer.Page p = renderer.openPage(page);
                int rotation = pageRotation(page);
                int pw = p.getWidth(), ph = p.getHeight();
                int maxLongEdge = 2300;
                float ratio = Math.min(2.4f, Math.min((float) maxLongEdge / pw, (float) maxLongEdge / ph));
                ratio = Math.max(1f, ratio);
                boolean side = rotation == 90 || rotation == 270;
                int w = Math.max(1, Math.round((side ? ph : pw) * ratio));
                int h = Math.max(1, Math.round((side ? pw : ph) * ratio));
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = renderMatrix(pw, ph, ratio, rotation);
                p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
            }
            Bitmap old = currentBitmap;
            currentBitmap = b;
            image.setImageBitmap(b);
            image.post(this::resetZoom);
            if (old != null && old != b && !old.isRecycled()) old.recycle();
            prefs.edit().putInt(pageKey(), page).apply();
            notifyPage();
            if (transformListener != null) transformListener.transformChanged();
        } catch (Exception e) {
            badge.setText("PDF-Seite konnte nicht geladen werden");
        }
    }

    public Bitmap renderThumbnail(int index, int targetW, int targetH) {
        targetW = Math.max(72, targetW); targetH = Math.max(100, targetH);
        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out); canvas.drawColor(0xffeceef2);
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return out;
        try {
            synchronized (renderLock) {
                PdfRenderer.Page p = renderer.openPage(index);
                int rotation = pageRotation(index);
                int pw = p.getWidth(), ph = p.getHeight();
                boolean side = rotation == 90 || rotation == 270;
                float rw = side ? ph : pw, rh = side ? pw : ph;
                float s = Math.min((targetW - 6f) / rw, (targetH - 6f) / rh);
                Matrix m = renderMatrix(pw, ph, s, rotation);
                float drawnW = rw * s, drawnH = rh * s;
                m.postTranslate((targetW - drawnW) / 2f, (targetH - drawnH) / 2f);
                p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void next() { if (renderer != null && page < renderer.getPageCount() - 1) { page++; showPage(); } }
    private void prev() { if (renderer != null && page > 0) { page--; showPage(); } }
    private void notifyPage() { updateBadge(); if (pageChangedListener != null) pageChangedListener.pageChanged(page, getPageCount()); }
    private void updateBadge() {
        if (renderer == null || renderer.getPageCount() <= 0) return;
        int pct = Math.round(getZoomFactor() * 100f);
        int rot = pageRotation(page);
        badge.setText((page + 1) + " / " + renderer.getPageCount() + (pct > 102 ? "  ·  " + pct + "%" : "") + (rot != 0 ? "  ·  ↻" + rot + "°" : ""));
    }
    private void applyMatrix() { image.setImageMatrix(matrix); updateBadge(); if (transformListener != null) transformListener.transformChanged(); }

    public Matrix getImageMatrixCopy() { return new Matrix(matrix); }
    public int getBitmapWidth() { return currentBitmap == null ? 0 : currentBitmap.getWidth(); }
    public int getBitmapHeight() { return currentBitmap == null ? 0 : currentBitmap.getHeight(); }

    /** Maps view coordinates into normalized coordinates of the currently displayed, rotated PDF page. */
    public float[] viewToNormalized(float vx,float vy) {
        if (currentBitmap == null) return null;
        Matrix inv = new Matrix();
        if (!matrix.invert(inv)) return null;
        float[] pt = new float[]{vx,vy}; inv.mapPoints(pt);
        if(pt[0]<0||pt[1]<0||pt[0]>currentBitmap.getWidth()||pt[1]>currentBitmap.getHeight())return null;
        return new float[]{clamp(pt[0] / Math.max(1f,currentBitmap.getWidth()),0f,1f), clamp(pt[1] / Math.max(1f,currentBitmap.getHeight()),0f,1f)};
    }

    /** Maps normalized page coordinates back into this view. */
    public float[] normalizedToView(float nx,float ny) {
        if (currentBitmap == null) return null;
        float[] pt = new float[]{clamp(nx,0f,1f)*currentBitmap.getWidth(),clamp(ny,0f,1f)*currentBitmap.getHeight()};
        matrix.mapPoints(pt); return pt;
    }

    /** Crops a region from the currently rendered PDF page. Region uses normalized page coordinates. */
    public Bitmap cropCurrentPage(RectF normalized,int maxLongEdge) {
        if (currentBitmap == null || normalized == null) return null;
        float l=clamp(Math.min(normalized.left,normalized.right),0f,1f), r=clamp(Math.max(normalized.left,normalized.right),0f,1f);
        float t=clamp(Math.min(normalized.top,normalized.bottom),0f,1f), b=clamp(Math.max(normalized.top,normalized.bottom),0f,1f);
        int x=Math.max(0,Math.min(currentBitmap.getWidth()-1,Math.round(l*currentBitmap.getWidth())));
        int y=Math.max(0,Math.min(currentBitmap.getHeight()-1,Math.round(t*currentBitmap.getHeight())));
        int w=Math.max(1,Math.min(currentBitmap.getWidth()-x,Math.round((r-l)*currentBitmap.getWidth())));
        int h=Math.max(1,Math.min(currentBitmap.getHeight()-y,Math.round((b-t)*currentBitmap.getHeight())));
        try {
            Bitmap cut=Bitmap.createBitmap(currentBitmap,x,y,w,h);
            int max=Math.max(w,h); if(maxLongEdge>0&&max>maxLongEdge){float sc=maxLongEdge/(float)max;Bitmap scaled=Bitmap.createScaledBitmap(cut,Math.max(1,Math.round(w*sc)),Math.max(1,Math.round(h*sc)),true);if(scaled!=cut)cut.recycle();return scaled;} return cut;
        } catch(Exception e){return null;}
    }

    /** Zooms to a saved favorite/marked region after its page is visible. */
    public void focusNormalizedRect(RectF normalized) {
        if (currentBitmap == null || normalized == null || getWidth()<=0 || getHeight()<=0) return;
        float l=clamp(Math.min(normalized.left,normalized.right),0f,1f), r=clamp(Math.max(normalized.left,normalized.right),0f,1f);
        float t=clamp(Math.min(normalized.top,normalized.bottom),0f,1f), b=clamp(Math.max(normalized.top,normalized.bottom),0f,1f);
        float bw=currentBitmap.getWidth(),bh=currentBitmap.getHeight();
        float rw=Math.max(1f,(r-l)*bw),rh=Math.max(1f,(b-t)*bh);
        float target=Math.min(maxScale,Math.max(minScale,Math.min(getWidth()*.72f/rw,getHeight()*.62f/rh)));
        float cx=(l+r)*.5f*bw,cy=(t+b)*.5f*bh;
        matrix.reset();matrix.postScale(target,target);matrix.postTranslate(getWidth()*.5f-cx*target,getHeight()*.5f-cy*target);currentScale=target;clampMatrix();applyMatrix();
    }

    private void clampMatrix() {
        if (currentBitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
        RectF r = new RectF(0, 0, currentBitmap.getWidth(), currentBitmap.getHeight());
        matrix.mapRect(r);

        // Free-canvas bounds: do not re-centre a narrow/short page. Keep only a small
        // grab area visible so the user cannot lose the page completely off-screen.
        float keepX = Math.min(getWidth() * .14f, 150f * getResources().getDisplayMetrics().density);
        float keepY = Math.min(getHeight() * .14f, 150f * getResources().getDisplayMetrics().density);
        float dx = 0f, dy = 0f;
        if (r.right < keepX) dx = keepX - r.right;
        else if (r.left > getWidth() - keepX) dx = getWidth() - keepX - r.left;
        if (r.bottom < keepY) dy = keepY - r.bottom;
        else if (r.top > getHeight() - keepY) dy = getHeight() - keepY - r.top;
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy);
    }

    private int pageRotation(int index) {
        int r = prefs.getInt(rotationKey(index), 0) % 360;
        if (r < 0) r += 360;
        return (r / 90) * 90;
    }

    private String rotationKey(int index) {
        return "pdf_rot_" + (uriString == null ? "none" : Integer.toHexString(uriString.hashCode())) + "_" + index;
    }

    /** Matrix mapping an unrotated PDF page directly into a rotated bitmap, without a second bitmap copy. */
    private static Matrix renderMatrix(int pw, int ph, float s, int rotation) {
        Matrix m = new Matrix();
        float[] v;
        switch (rotation) {
            case 90:
                v = new float[]{0f, -s, ph * s,  s, 0f, 0f,  0f, 0f, 1f};
                break;
            case 180:
                v = new float[]{-s, 0f, pw * s,  0f, -s, ph * s,  0f, 0f, 1f};
                break;
            case 270:
                v = new float[]{0f, s, 0f,  -s, 0f, pw * s,  0f, 0f, 1f};
                break;
            default:
                v = new float[]{s, 0f, 0f,  0f, s, 0f,  0f, 0f, 1f};
                break;
        }
        m.setValues(v);
        return m;
    }

    private String pageKey() { return "pdf_page_" + (uriString == null ? "none" : Integer.toHexString(uriString.hashCode())); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float multiSpan(MotionEvent e) {
        int n = Math.min(5, e.getPointerCount());
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += e.getX(i); cy += e.getY(i); }
        cx /= n; cy /= n;
        float sum = 0;
        for (int i = 0; i < n; i++) sum += (float) Math.hypot(e.getX(i) - cx, e.getY(i) - cy);
        return sum / n;
    }

    public void close() {
        image.setImageDrawable(null);
        try { if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle(); } catch (Exception ignored) { }
        currentBitmap = null;
        synchronized (renderLock) {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
            try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) { }
            renderer = null; descriptor = null;
        }
    }
}
