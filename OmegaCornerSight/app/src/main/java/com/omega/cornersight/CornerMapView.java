package com.omega.cornersight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CornerMapView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        latest = null;
        invalidate();
    }

    public void push(PenumbraProcessor.Result r) {
        latest = r;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF061018);
        c.drawRect(0, 0, w, h, p);

        p.setColor(Color.WHITE);
        p.setTextSize(28f);
        c.drawText("HIDDEN SIDE", 22f, 38f, p);

        drawCornerRoom(c, w, h);

        if (latest == null) {
            drawCenterText(c, "CALIBRATE FIRST", h * 0.53f, 0xFFFFCC55, 30f);
            return;
        }

        if (latest.calibrating) {
            int pct = Math.min(100, latest.calibrationFrame * 100 / PenumbraProcessor.CAL_FRAMES);
            drawCenterText(c, "LEARNING EMPTY CORNER", h * 0.48f, 0xFFFFCC55, 28f);
            drawCenterText(c, pct + "%", h * 0.57f, Color.WHITE, 42f);
            return;
        }

        if (latest.frozen) {
            drawCenterText(c, "PHONE MOVED", h * 0.48f, 0xFFFF8844, 34f);
            drawCenterText(c, "HOLD STILL", h * 0.57f, Color.WHITE, 30f);
            return;
        }

        float conf = latest.confidence;
        if (conf < 0.18f) {
            drawCenterText(c, "NO HIDDEN MOTION", h * 0.53f, 0xFF8DEEFF, 30f);
            drawBottomStatus(c, w, h, "watching indirect light", 0xFF8DEEFF);
            return;
        }

        List<Peak> peaks = topPeaks(latest.row);
        if (peaks.isEmpty()) peaks.add(new Peak((latest.angleDeg + 70f) / 140f, conf));

        float cornerX = w * 0.23f;
        float cornerY = h * 0.71f;
        float baseR = Math.min(w, h) * 0.43f;
        int drawn = 0;
        for (Peak peak : peaks) {
            if (peak.strength < 0.24f || drawn >= 3) continue;
            float angle = -70f + 140f * peak.pos;
            double rad = Math.toRadians(angle - 90f);
            float r = baseR * (dualMode ? (0.42f + 0.50f * latest.relY) : 0.72f);
            float x = cornerX + (float)Math.cos(rad) * r;
            float y = cornerY + (float)Math.sin(rad) * r;
            x = clamp(x, w * 0.16f, w * 0.92f);
            y = clamp(y, h * 0.16f, h * 0.76f);
            drawTarget(c, x, y, peak.strength, latest.angularSpeed);
            drawn++;
        }

        String strength = conf > 0.72f ? "STRONG" : conf > 0.43f ? "MEDIUM" : "WEAK";
        String direction = Math.abs(latest.angularSpeed) < 4f ? "STILL / SLOW" : latest.angularSpeed > 0f ? "MOVING RIGHT" : "MOVING LEFT";
        int targets = Math.max(1, Math.min(3, drawn > 0 ? drawn : latest.clusters));

        p.setTextSize(31f);
        p.setColor(0xFFFF6688);
        String targetLine = targets + (targets == 1 ? " TARGET" : " TARGETS");
        c.drawText(targetLine, 24f, h - 94f, p);

        p.setTextSize(25f);
        p.setColor(Color.WHITE);
        c.drawText(direction + "  •  " + strength, 24f, h - 58f, p);

        p.setTextSize(19f);
        p.setColor(0xFF9AB4BF);
        String mode = dualMode ? "2D RELATIVE POSITION" : "ANGLE ONLY";
        c.drawText(mode + "  •  confidence " + Math.round(conf * 100f) + "%", 24f, h - 26f, p);
    }

    private void drawCornerRoom(Canvas c, int w, int h) {
        float cornerX = w * 0.23f;
        float cornerY = h * 0.71f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0C2430);
        Path hidden = new Path();
        hidden.moveTo(cornerX, cornerY);
        hidden.lineTo(w, cornerY);
        hidden.lineTo(w, 68f);
        hidden.lineTo(cornerX, 68f);
        hidden.close();
        c.drawPath(hidden, p);

        p.setColor(0xFF17333F);
        c.drawRect(0, cornerY, w, h - 125f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(12f);
        p.setColor(0xFFB5C6CD);
        c.drawLine(0, cornerY, cornerX, cornerY, p);
        c.drawLine(cornerX, cornerY, cornerX, 68f, p);

        p.setStyle(Paint.Style.FILL);
        p.setTextSize(20f);
        p.setColor(0xFF90AAB3);
        c.drawText("VISIBLE", 18f, cornerY + 36f, p);
        p.setColor(0xFF8DEEFF);
        c.drawText("OUT OF SIGHT", cornerX + 22f, 98f, p);

        p.setColor(0xFFFFCC55);
        c.drawCircle(cornerX, cornerY, 9f, p);
        p.setTextSize(17f);
        c.drawText("CORNER", cornerX + 14f, cornerY - 12f, p);
    }

    private void drawTarget(Canvas c, float x, float y, float strength, float speed) {
        float s = clamp(strength, 0f, 1f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(45 + (int)(90*s), 255, 50, 90));
        c.drawCircle(x, y, 36f + 20f*s, p);
        p.setColor(0xFFFF4466);
        c.drawCircle(x, y, 13f + 7f*s, p);

        if (Math.abs(speed) > 4f) {
            float dir = speed > 0f ? 1f : -1f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(6f);
            p.setColor(0xFFFFCC55);
            float x2 = x + dir * 72f;
            c.drawLine(x, y, x2, y, p);
            Path arrow = new Path();
            arrow.moveTo(x2, y);
            arrow.lineTo(x2 - dir*18f, y - 12f);
            arrow.lineTo(x2 - dir*18f, y + 12f);
            arrow.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(arrow, p);
        }
    }

    private void drawCenterText(Canvas c, String s, float y, int color, float size) {
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, getWidth() * 0.5f, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBottomStatus(Canvas c, int w, int h, String s, int color) {
        p.setTextSize(21f);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, w * 0.5f, h - 34f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static final class Peak {
        final float pos;
        final float strength;
        Peak(float pos, float strength) { this.pos = pos; this.strength = strength; }
    }

    private static List<Peak> topPeaks(float[] row) {
        List<Peak> peaks = new ArrayList<>();
        if (row == null || row.length < 3) return peaks;
        for (int i = 1; i < row.length - 1; i++) {
            float v = row[i];
            if (v >= row[i-1] && v >= row[i+1] && v > 0.18f) {
                peaks.add(new Peak(i / (float)(row.length - 1), v));
            }
        }
        peaks.sort(Comparator.comparingDouble((Peak a) -> a.strength).reversed());
        if (peaks.size() > 3) return new ArrayList<>(peaks.subList(0, 3));
        return peaks;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
