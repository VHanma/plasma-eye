package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Transparent, non-interactive HUD used by the live screen scanner. */
public class XRayVisionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<XRayBridge.Hit> hits = new ArrayList<>();
    private int frameW = 1;
    private int frameH = 1;
    private int sensitivity = 66;
    private String audioStatus = "AUDIO: STARTING";
    private boolean audioAlert = false;
    private long audioFlashUntil = 0L;

    public XRayVisionView(Context c) {
        super(c);
        init();
    }

    public XRayVisionView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    public synchronized void setSensitivity(int value) {
        sensitivity = Math.max(0, Math.min(100, value));
        postInvalidate();
    }

    public synchronized void setHits(List<XRayBridge.Hit> newHits, int sourceW, int sourceH) {
        hits.clear();
        if (newHits != null) hits.addAll(newHits);
        frameW = Math.max(1, sourceW);
        frameH = Math.max(1, sourceH);
        postInvalidate();
    }

    public synchronized void setAudioStatus(String status) {
        setAudioStatus(status, false);
    }

    public synchronized void setAudioStatus(String status, boolean alert) {
        audioStatus = status == null ? "AUDIO" : status;
        audioAlert = alert;
        if (alert) audioFlashUntil = System.currentTimeMillis() + 900L;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float sx = w / (float)frameW;
        float sy = h / (float)frameH;

        for (int i = 0; i < hits.size(); i++) {
            XRayBridge.Hit hit = hits.get(i);
            Rect r = hit.rect;
            RectF box = new RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy);
            drawHit(canvas, box, hit, i);
        }

        drawHeader(canvas, w);
        drawAudio(canvas, w, h);

        if (audioAlert || !hits.isEmpty()) postInvalidateDelayed(80L);
    }

    private void drawHit(Canvas c, RectF box, XRayBridge.Hit hit, int index) {
        int color;
        if ("TEMPORAL".equals(hit.kind)) color = Color.rgb(255, 90, 225);
        else if ("CHROMA".equals(hit.kind)) color = Color.rgb(255, 210, 50);
        else if ("MICRO".equals(hit.kind)) color = Color.rgb(0, 235, 255);
        else color = Color.rgb(80, 255, 135);

        float pulse = 1f + 0.12f * (float)Math.sin(System.currentTimeMillis() / 90.0 + index);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.2f * pulse);
        paint.setColor(color);
        c.drawRect(box, paint);

        float corner = Math.min(28f, Math.min(box.width(), box.height()) * 0.28f);
        paint.setStrokeWidth(7f);
        c.drawLine(box.left, box.top, box.left + corner, box.top, paint);
        c.drawLine(box.left, box.top, box.left, box.top + corner, paint);
        c.drawLine(box.right, box.top, box.right - corner, box.top, paint);
        c.drawLine(box.right, box.top, box.right, box.top + corner, paint);
        c.drawLine(box.left, box.bottom, box.left + corner, box.bottom, paint);
        c.drawLine(box.left, box.bottom, box.left, box.bottom - corner, paint);
        c.drawLine(box.right, box.bottom, box.right - corner, box.bottom, paint);
        c.drawLine(box.right, box.bottom, box.right, box.bottom - corner, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(25f);
        paint.setColor(Color.argb(220, 0, 0, 0));
        float labelW = paint.measureText(hit.kind + "  " + hit.score) + 22f;
        float top = Math.max(0, box.top - 34f);
        c.drawRect(box.left, top, Math.min(getWidth(), box.left + labelW), top + 34f, paint);
        paint.setColor(color);
        c.drawText(hit.kind + "  " + hit.score, box.left + 10f, top + 25f, paint);
    }

    private void drawHeader(Canvas c, int w) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(175, 0, 0, 0));
        c.drawRoundRect(14, 16, Math.min(w - 14, 430), 104, 16, 16, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(25f);
        c.drawText("BEHIND THE CURTAIN • LIVE", 30, 50, paint);

        paint.setColor(Color.rgb(0, 235, 210));
        paint.setTextSize(19f);
        c.drawText("hits " + hits.size() + "   sensitivity " + sensitivity, 30, 82, paint);
    }

    private void drawAudio(Canvas c, int w, int h) {
        boolean flash = System.currentTimeMillis() < audioFlashUntil;
        int color = (audioAlert || flash) ? Color.rgb(255, 80, 80) : Color.rgb(185, 205, 220);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((audioAlert || flash) ? 220 : 165, 0, 0, 0));
        c.drawRoundRect(14, h - 82, w - 14, h - 18, 16, 16, paint);

        paint.setTextSize(20f);
        paint.setColor(color);
        c.drawText(audioStatus, 30, h - 42, paint);

        if (audioAlert && !flash) audioAlert = false;
    }
}