package de.millione.schulranzen;

import android.content.Context;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;

import java.util.ArrayList;
import java.util.List;

/** Optional on-device handwriting recognizer used only for calculator hints. */
public final class MathInkRecognizer {
    public interface Callback { void done(List<String> candidates, String error); }
    private final Context context;
    private DigitalInkRecognitionModel model;
    private DigitalInkRecognizer recognizer;
    private volatile boolean preparing;
    private volatile boolean ready;
    private final ArrayList<Callback> prepareCallbacks = new ArrayList<>();

    public MathInkRecognizer(Context c) { context = c.getApplicationContext(); }
    public boolean isReady() { return ready; }

    public void prepare(Callback cb) {
        synchronized (this) {
            if (ready) { if (cb != null) cb.done(new ArrayList<>(), null); return; }
            if (cb != null) prepareCallbacks.add(cb);
            if (preparing) return;
            preparing = true;
        }
        try {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("de-DE");
            if (id == null) id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
            if (id == null) throw new IllegalStateException("Kein Handschriftmodell");
            model = DigitalInkRecognitionModel.builder(id).build();
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
            RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> finishPrepare(true, null))
                    .addOnFailureListener(e -> finishPrepare(false, e.getClass().getSimpleName()));
        } catch (Exception e) {
            finishPrepare(false, e.getClass().getSimpleName());
        }
    }

    private void finishPrepare(boolean ok, String error) {
        ArrayList<Callback> callbacks;
        synchronized (this) {
            preparing = false; ready = ok; callbacks = new ArrayList<>(prepareCallbacks); prepareCallbacks.clear();
        }
        for (Callback cb : callbacks) cb.done(new ArrayList<>(), error);
    }

    public void recognize(NotebookView.InkSnapshot snap, Callback cb) {
        if (snap == null || snap.strokes.isEmpty()) return;
        if (!ready || recognizer == null) {
            prepare((x, err) -> { if (err == null && ready) recognize(snap, cb); else if (cb != null) cb.done(new ArrayList<>(), err); });
            return;
        }
        Ink.Builder ink = Ink.builder();
        float pad = 24f;
        float minX = snap.minX == Float.MAX_VALUE ? 0f : snap.minX;
        float minY = snap.minY == Float.MAX_VALUE ? 0f : snap.minY;
        float width = Math.max(140f, snap.maxX - minX + pad * 2f);
        float height = Math.max(72f, snap.maxY - minY + pad * 2f);
        for (NotebookView.InkStrokeData s : snap.strokes) {
            Ink.Stroke.Builder sb = Ink.Stroke.builder();
            for (NotebookView.InkPointData p : s.points) sb.addPoint(Ink.Point.create(p.x - minX + pad, p.y - minY + pad, p.t));
            ink.addStroke(sb.build());
        }
        RecognitionContext rc = RecognitionContext.builder().setPreContext("0123456789 + - * × · / ÷ : [ ] ( ) = ^ sqrt √").setWritingArea(new WritingArea(width, height)).build();
        recognizer.recognize(ink.build(), rc)
                .addOnSuccessListener(r -> {
                    ArrayList<String> out = new ArrayList<>();
                    int n = Math.min(24, r.getCandidates().size());
                    for (int i = 0; i < n; i++) out.add(r.getCandidates().get(i).getText());
                    if (cb != null) cb.done(out, null);
                })
                .addOnFailureListener(e -> { if (cb != null) cb.done(new ArrayList<>(), e.getClass().getSimpleName()); });
    }

    public void close() {
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) {}
        recognizer = null; ready = false;
    }
}
