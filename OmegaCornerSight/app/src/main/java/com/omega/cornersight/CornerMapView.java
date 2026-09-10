package com.omega.cornersight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class CornerMapView extends View {
    private static final int H = 160;
    private final float[][] history = new float[H][PenumbraProcessor.BINS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int head = 0;
    private PenumbraProcessor.Result latest;
    private boolean dualMode = false;

    public CornerMapView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    public void setDualMode(boolean dual) {
        dualMode = dual;
        invalidate();
    }

    public void clearHistory() {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < PenumbraProcessor.BINS; x++) history[y][x] = 0f;
        }
        head = 0;
        invalidate();
    }

    public void push(PenumbraProcessor.Result r) {
        latest = r;
        if (!r.calibrating && !r.frozen) {
            System.arraycopy(r.row, 0, history[head], 0, PenumbraProcessor.BINS);
            head = (head + 1) % H;
        }
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        int topPad = 46;
        int bottomPad = 58;
        int heatBottom = dualMode ? (int)(h * 0.63f) : h - bottomPad;
        float cw = w / (float) PenumbraProcessor.BINS;
        float ch = Math.max(1f, (heatBottom - topPad) / (float) H);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF071018);
        c.drawRect(0, 0, w, h, p);

        for (int row = 0; row < H; row++) {
            int src = (head + row) % H;
            float y0 = topPad + row * ch;
            for (int x = 0; x < PenumbraProcessor.BINS; x++) {
                float v = history[src][x];
                if (v < 0.035f) continue;
                float hue = 220f - 190f * v;
                int alpha = 40 + (int)(215f * Math.min(1f, v));
                p.setColor(Color.HSVToColor(alpha, new float[]{hue, 0.92f, Math.min(1f, 0.25f + 0.9f*v)}));
                c.drawRect(x*cw, y0, (x+1)*cw + 1f, y0 + ch + 1f, p);
            }
        }

        p.setColor(0x5533DDFF);
        p.setStrokeWidth(1f);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i < 8; i++) {
            float x = w * i / 8f;
            c.drawLine(x, topPad, x, heatBottom, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(26f);
        p.setColor(Color.WHITE);
        c.drawText("HIDDEN-SCENE PENUMBRA RECONSTRUCTION", 18f, 31f, p);

        p.setTextSize(20f);
        p.setColor(0xFF9FEFFF);
        c.drawText("-70°", 8f, heatBottom + 27f, p);
        c.drawText("0°", w*0.5f - 10f, heatBottom + 27f, p);
        c.drawText("+70°", w - 58f, heatBottom + 27f, p);

        if (latest != null) {
            if (latest.calibrating) {
                p.setTextSize(30f);
                p.setColor(0xFFFFCC33);
                String s = "LEARNING NOISE  " + latest.calibrationFrame + "/" + PenumbraProcessor.CAL_FRAMES;
                c.drawText(s, 18f, h - 18f, p);
            } else if (latest.frozen) {
                p.setTextSize(28f);
                p.setColor(0xFFFF8844);
                c.drawText("PHONE MOVED • RECONSTRUCTION FROZEN", 18f, h - 18f, p);
            } else {
                p.setTextSize(23f);
                p.setColor(Color.WHITE);
                String s = String.format(java.util.Locale.US,
                        "angle %+4.1f°   speed %+4.1f°/s   conf %3.0f%%   tracks %d",
                        latest.angleDeg, latest.angularSpeed, latest.confidence*100f, latest.clusters);
                c.drawText(s, 18f, dualMode ? heatBottom + 54f : h - 18f, p);

                if (latest.confidence > 0.15f) {
                    float tx = (latest.angleDeg + 70f) / 140f * w;
                    p.setColor(0xFFFF4466);
                    c.drawCircle(tx, heatBottom - 10f, 10f + 10f*latest.confidence, p);
                }
            }
        }

        if (dualMode) drawDualMap(c, w, h, heatBottom + 70);
    }

    private void drawDualMap(Canvas c, int w, int h, int top) {
        int pad = 24;
        RectF box = new RectF(pad, top, w - pad, h - pad);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFF33CCFF);
        c.drawRect(box, p);
        p.setColor(0x4433CCFF);
        c.drawLine(box.centerX(), box.top, box.centerX(), box.bottom, p);
        c.drawLine(box.left, box.centerY(), box.right, box.centerY(), p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(19f);
        p.setColor(0xFF9FEFFF);
        c.drawText("DUAL-EDGE RELATIVE 2D FUSION", box.left + 8f, box.top + 22f, p);
        if (latest != null && !latest.calibrating && !latest.frozen && latest.confidence > 0.12f) {
            float x = box.centerX() + latest.relX * box.width() * 0.43f;
            float y = box.bottom - latest.relY * box.height() * 0.72f;
            p.setColor(0xFFFF4466);
            c.drawCircle(x, y, 12f + 15f*latest.confidence, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0x99FF4466);
            c.drawCircle(x, y, 30f + 35f*(1f-latest.confidence), p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
