package com.vhanma.omegaechoframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

public final class EchoRadarView extends View {
    public static final int MODE_ROOM = 0;
    public static final int MODE_MOTION = 1;
    public static final int MODE_MICRO = 2;

    private static final int HISTORY = 180;
    private static final int BINS = AcousticEngine.BINS;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] heatPixels = new int[BINS * HISTORY];
    private final Bitmap heat = Bitmap.createBitmap(BINS, HISTORY, Bitmap.Config.ARGB_8888);
    private final float[] raw = new float[BINS];
    private final float[] dyn = new float[BINS];
    private final float[] microHistory = new float[180];
    private final float[] velocityHistory = new float[180];
    private final float[] rangeHistory = new float[180];
    private int mode = MODE_MOTION;
    private boolean frozen;
    private float gain = 1.0f;
    private float currentRange = -1f;
    private float currentMicro;
    private float currentVelocity;
    private float confidence;

    public EchoRadarView(Context context) { this(context, null); }
    public EchoRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        text.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
        Arrays.fill(heatPixels, Color.BLACK);
        heat.setPixels(heatPixels, 0, BINS, 0, 0, BINS, HISTORY);
    }

    public void setMode(int mode) {
        this.mode = mode;
        clearHistory();
        invalidate();
    }

    public int getMode() { return mode; }
    public void setGain(float gain) { this.gain = Math.max(0.4f, Math.min(3.0f, gain)); }
    public void toggleFreeze() { frozen = !frozen; invalidate(); }
    public boolean isFrozen() { return frozen; }

    public void clearHistory() {
        Arrays.fill(heatPixels, Color.BLACK);
        Arrays.fill(microHistory, 0f);
        Arrays.fill(velocityHistory, 0f);
        Arrays.fill(rangeHistory, -1f);
        heat.setPixels(heatPixels, 0, BINS, 0, 0, BINS, HISTORY);
    }

    public void push(AcousticEngine.Frame frame) {
        if (frozen) return;
        System.arraycopy(frame.raw, 0, raw, 0, Math.min(raw.length, frame.raw.length));
        System.arraycopy(frame.dynamic, 0, dyn, 0, Math.min(dyn.length, frame.dynamic.length));
        currentRange = frame.rangeMeters;
        currentMicro = frame.microMm;
        currentVelocity = frame.radialVelocity;
        confidence = frame.confidence;

        System.arraycopy(heatPixels, BINS, heatPixels, 0, BINS * (HISTORY - 1));
        float[] src = mode == MODE_ROOM ? raw : dyn;
        int row = (HISTORY - 1) * BINS;
        for (int b = 0; b < BINS; b++) heatPixels[row + b] = heatColor(src[b] * gain, mode == MODE_ROOM);
        heat.setPixels(heatPixels, 0, BINS, 0, 0, BINS, HISTORY);

        shift(microHistory, currentMicro);
        shift(velocityHistory, currentVelocity);
        shift(rangeHistory, currentRange);
        invalidate();
    }

    private static void shift(float[] a, float value) {
        System.arraycopy(a, 1, a, 0, a.length - 1);
        a[a.length - 1] = value;
    }

    private static int heatColor(float value, boolean room) {
        float v = clamp(value, 0f, 1f);
        if (v < 0.015f) return Color.rgb(1, 3, 7);
        int r, g, b;
        if (room) {
            r = (int) (30 + 160 * v * v);
            g = (int) (60 + 195 * v);
            b = (int) (90 + 165 * Math.sqrt(v));
        } else {
            r = (int) (20 + 235 * v);
            g = (int) (15 + 230 * Math.min(1f, v * 1.4f));
            b = (int) (55 + 200 * (1f - Math.abs(v - 0.55f)));
        }
        return Color.rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        if (w < 20 || h < 20) return;

        float left = 44f;
        float right = w - 12f;
        float top = 18f;
        float heatBottom = h * 0.56f;
        Rect src = new Rect(0, 0, BINS, HISTORY);
        RectF dst = new RectF(left, top, right, heatBottom);
        p.setFilterBitmap(false);
        c.drawBitmap(heat, src, dst, p);

        drawRangeGrid(c, left, right, top, heatBottom);
        drawCurrentProfile(c, left, right, heatBottom + 16f, h * 0.73f);
        drawRangeShells(c, w, h, h * 0.76f);

        if (mode == MODE_MICRO) drawMicroTrace(c, left, right, h * 0.76f, h - 28f);
        else drawVelocityTrace(c, left, right, h * 0.76f, h - 28f);

        if (frozen) {
            p.setColor(Color.argb(165, 0, 0, 0));
            c.drawRect(0, 0, w, h, p);
            text.setTextSize(34f);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("FROZEN", w / 2f, h / 2f, text);
        }
    }

    private void drawRangeGrid(Canvas c, float left, float right, float top, float bottom) {
        p.setStrokeWidth(1f);
        text.setTextSize(20f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.rgb(145, 190, 205));
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            p.setColor(Color.argb(80, 100, 180, 210));
            c.drawLine(x, top, x, bottom, p);
            float meters = AcousticEngine.MAX_RANGE_METERS * i / 4f;
            c.drawText(String.format(java.util.Locale.US, "%.1fm", meters), x, bottom - 7f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(18f);
        text.setColor(Color.rgb(90, 190, 210));
        c.drawText(modeName() + "  •  newest echo at bottom", left + 4f, top + 22f, text);
    }

    private void drawCurrentProfile(Canvas c, float left, float right, float top, float bottom) {
        float[] src = mode == MODE_ROOM ? raw : dyn;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.3f);
        p.setColor(mode == MODE_ROOM ? Color.rgb(80, 225, 255) : Color.rgb(255, 110, 230));
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < BINS; i++) {
            float x = left + (right - left) * i / (BINS - 1f);
            float y = bottom - clamp(src[i] * gain, 0f, 1f) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(65, 70, 150, 180));
        c.drawRect(left, bottom, right, bottom + 1f, p);
    }

    private void drawRangeShells(Canvas c, int w, int h, float top) {
        float cx = w * 0.5f;
        float cy = h + 8f;
        float maxR = Math.min(w * 0.48f, h * 0.23f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        for (int i = 1; i <= 5; i++) {
            p.setColor(Color.argb(50, 70, 180, 210));
            float r = maxR * i / 5f;
            c.drawCircle(cx, cy, r, p);
        }
        float[] src = mode == MODE_ROOM ? raw : dyn;
        for (int i = 2; i < BINS; i += 2) {
            float v = clamp(src[i] * gain, 0f, 1f);
            if (v < (mode == MODE_ROOM ? 0.42f : 0.20f)) continue;
            float distance = (AcousticEngine.MIN_ECHO_SAMPLES + i * AcousticEngine.BIN_STEP_SAMPLES)
                    * 343f / (2f * AcousticEngine.SAMPLE_RATE);
            float r = maxR * distance / AcousticEngine.MAX_RANGE_METERS;
            p.setStrokeWidth(1.5f + 5f * v);
            p.setColor(Color.argb((int) (55 + 190 * v), 100 + (int) (155 * v), 70, 240));
            c.drawCircle(cx, cy, r, p);
        }
        p.setStyle(Paint.Style.FILL);
        if (currentRange > 0f) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(20f);
            text.setColor(Color.WHITE);
            c.drawText(String.format(java.util.Locale.US, "LOCK %.2f m   CONF %.0f%%", currentRange, confidence * 100f),
                    cx, top + 20f, text);
        }
    }

    private void drawMicroTrace(Canvas c, float left, float right, float top, float bottom) {
        float mid = (top + bottom) * 0.5f;
        p.setStrokeWidth(1f);
        p.setColor(Color.argb(80, 130, 180, 200));
        c.drawLine(left, mid, right, mid, p);
        p.setStrokeWidth(2.5f);
        p.setColor(Color.rgb(255, 195, 80));
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < microHistory.length; i++) {
            float x = left + (right - left) * i / (microHistory.length - 1f);
            float y = mid - clamp(microHistory[i] / 18f, -1f, 1f) * (bottom - top) * 0.45f;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(18f);
        text.setColor(Color.rgb(255, 210, 120));
        c.drawText(String.format(java.util.Locale.US, "PHASE MICRO-MOTION  %+5.2f mm", currentMicro), left, top + 18f, text);
    }

    private void drawVelocityTrace(Canvas c, float left, float right, float top, float bottom) {
        float mid = (top + bottom) * 0.5f;
        p.setStrokeWidth(1f);
        p.setColor(Color.argb(80, 130, 180, 200));
        c.drawLine(left, mid, right, mid, p);
        p.setStrokeWidth(2.5f);
        p.setColor(Color.rgb(80, 255, 165));
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < velocityHistory.length; i++) {
            float x = left + (right - left) * i / (velocityHistory.length - 1f);
            float y = mid - clamp(velocityHistory[i] / 1.5f, -1f, 1f) * (bottom - top) * 0.45f;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(18f);
        text.setColor(Color.rgb(120, 255, 185));
        c.drawText(String.format(java.util.Locale.US, "RADIAL MOTION  %+5.2f m/s", currentVelocity), left, top + 18f, text);
    }

    private String modeName() {
        if (mode == MODE_ROOM) return "ROOM ECHO";
        if (mode == MODE_MICRO) return "MICRO PHASE";
        return "MOTION GHOST";
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
