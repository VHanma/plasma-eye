package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

public class XRayVisionView extends View {
    private static class Node {
        String id;
        float value;
        float baseline;
        float x;
        float y;
        long lastSeen;
        boolean calibrated;

        Node(String id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Node> nodes = new HashMap<>();

    private float[] memory;
    private int cols = 72;
    private int rows = 120;

    private float sensitivity = 1.0f;
    private boolean frozen = false;
    private long flashUntil = 0L;
    private long lastTap = 0L;
    private float downY = 0f;

    public XRayVisionView(Context c) {
        super(c);
        init();
    }

    public XRayVisionView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public synchronized void acceptSample(String id, float value, float x, float y) {
        if (id == null || id.length() == 0) return;

        x = clamp01(x);
        y = clamp01(y);

        Node n = nodes.get(id);
        if (n == null) {
            n = new Node(id, x, y);
            n.baseline = value;
            n.value = value;
            n.calibrated = true;
            nodes.put(id, n);
        }

        n.x = x;
        n.y = y;
        n.value = value;
        n.lastSeen = System.currentTimeMillis();

        if (!n.calibrated) {
            n.baseline = value;
            n.calibrated = true;
        }

        postInvalidate();
    }

    public synchronized void calibrate() {
        for (Node n : nodes.values()) {
            n.baseline = n.value;
            n.calibrated = true;
        }

        if (memory != null) {
            for (int i = 0; i < memory.length; i++) memory[i] = 0f;
        }

        flashUntil = System.currentTimeMillis() + 900L;
        postInvalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = System.currentTimeMillis();

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downY = e.getY();
            return true;
        }

        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dy = e.getY() - downY;

            if (Math.abs(dy) > 90f) {
                if (dy < 0) sensitivity = Math.min(3.0f, sensitivity + 0.18f);
                else sensitivity = Math.max(0.20f, sensitivity - 0.18f);

                flashUntil = now + 700L;
                invalidate();
                return true;
            }

            if (now - lastTap < 330L) {
                frozen = !frozen;
                flashUntil = now + 700L;
                lastTap = 0L;
                invalidate();
                return true;
            }

            lastTap = now;
            return true;
        }

        return true;
    }

    @Override
    public boolean performLongClick() {
        calibrate();
        return super.performLongClick();
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        rows = Math.max(44, (int)(cols * (h / (float)w)));

        if (memory == null || memory.length != cols * rows) {
            memory = new float[cols * rows];
        }

        float[] field = new float[cols * rows];
        buildField(field);

        if (!frozen) updateMemory(field);

        drawContours(c, memory, w, h);

        long now = System.currentTimeMillis();
        drawSweep(c, w, h, now);

        if (now < flashUntil || nodes.isEmpty()) {
            drawStatus(c, w, h);
        }

        postInvalidateDelayed(33);
    }

    private void buildField(float[] field) {
        long now = System.currentTimeMillis();

        for (Node n : nodes.values()) {
            if (now - n.lastSeen > 7000L) continue;

            float delta = Math.abs(n.value - n.baseline);

            // RSSI dBm shadow changes of 3 to 12 dB are meaningful.
            float strength = clamp01((delta - 1.5f) / 12.0f) * sensitivity;
            if (strength <= 0.01f) continue;

            addSource(field, n.x, n.y, strength, 0.105f);

            // Shadow echo: creates a second hard contour so paths feel spatial, not dot-like.
            float mx = clamp01(1f - n.x * 0.72f + hash01(n.id + "x") * 0.22f);
            float my = clamp01(1f - n.y * 0.72f + hash01(n.id + "y") * 0.22f);
            addSource(field, mx, my, strength * 0.55f, 0.075f);
        }

        normalize(field);
    }

    private void updateMemory(float[] field) {
        for (int i = 0; i < field.length; i++) {
            float v = field[i];

            if (v > memory[i]) {
                memory[i] = memory[i] * 0.66f + v * 0.34f;
            } else {
                memory[i] *= 0.987f;
            }

            if (memory[i] < 0.015f) memory[i] = 0f;
            if (memory[i] > 1f) memory[i] = 1f;
        }
    }

    private void addSource(float[] field, float sx, float sy, float strength, float radius) {
        float r2 = radius * radius;

        for (int y = 0; y < rows; y++) {
            float ny = y / (float)(rows - 1);

            for (int x = 0; x < cols; x++) {
                float nx = x / (float)(cols - 1);
                float dx = nx - sx;
                float dy = ny - sy;
                float d2 = dx * dx + dy * dy;

                float v = strength * (float)Math.exp(-d2 / Math.max(0.0001f, r2));
                field[y * cols + x] += v;
            }
        }
    }

    private void drawContours(Canvas c, float[] field, int screenW, int screenH) {
        drawContourLevel(c, field, screenW, screenH, 0.28f, Color.argb(245, 255, 255, 255), 5.8f);
        drawContourLevel(c, field, screenW, screenH, 0.43f, Color.argb(235, 40, 210, 255), 4.0f);
        drawContourLevel(c, field, screenW, screenH, 0.61f, Color.argb(235, 255, 130, 40), 2.8f);
        drawContourLevel(c, field, screenW, screenH, 0.78f, Color.argb(255, 255, 255, 255), 1.7f);
    }

    private void drawContourLevel(Canvas c, float[] field, int screenW, int screenH, float level, int color, float stroke) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(color);

        float cellW = screenW / (float)(cols - 1);
        float cellH = screenH / (float)(rows - 1);

        for (int y = 0; y < rows - 1; y++) {
            for (int x = 0; x < cols - 1; x++) {
                float v0 = field[y * cols + x];
                float v1 = field[y * cols + x + 1];
                float v2 = field[(y + 1) * cols + x + 1];
                float v3 = field[(y + 1) * cols + x];

                float min = Math.min(Math.min(v0, v1), Math.min(v2, v3));
                float max = Math.max(Math.max(v0, v1), Math.max(v2, v3));

                if (level < min || level > max) continue;

                float[] xs = new float[4];
                float[] ys = new float[4];
                int count = 0;

                if (crosses(v0, v1, level)) {
                    float t = interp(v0, v1, level);
                    xs[count] = (x + t) * cellW;
                    ys[count] = y * cellH;
                    count++;
                }

                if (crosses(v1, v2, level)) {
                    float t = interp(v1, v2, level);
                    xs[count] = (x + 1) * cellW;
                    ys[count] = (y + t) * cellH;
                    count++;
                }

                if (crosses(v3, v2, level)) {
                    float t = interp(v3, v2, level);
                    xs[count] = (x + t) * cellW;
                    ys[count] = (y + 1) * cellH;
                    count++;
                }

                if (crosses(v0, v3, level)) {
                    float t = interp(v0, v3, level);
                    xs[count] = x * cellW;
                    ys[count] = (y + t) * cellH;
                    count++;
                }

                if (count == 2) {
                    c.drawLine(xs[0], ys[0], xs[1], ys[1], p);
                } else if (count == 4) {
                    c.drawLine(xs[0], ys[0], xs[1], ys[1], p);
                    c.drawLine(xs[2], ys[2], xs[3], ys[3], p);
                }
            }
        }
    }

    private void drawSweep(Canvas c, int w, int h, long now) {
        float phase = (now % 2200L) / 2200f;
        float r = phase * (float)Math.sqrt(w * w + h * h);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.0f + 4.0f * (1f - phase));
        p.setColor(Color.argb((int)(155 * (1f - phase)), 255, 255, 255));
        c.drawCircle(w / 2f, h / 2f, r, p);
    }

    private void drawStatus(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(170, 0, 0, 0));
        c.drawRoundRect(24, 24, w - 24, 138, 18, 18, p);

        p.setTextSize(28f);
        p.setColor(Color.WHITE);
        c.drawText("XRAY BRIDGE", 48, 64, p);

        p.setTextSize(20f);
        p.setColor(Color.argb(245, 0, 235, 255));
        c.drawText("nodes " + nodes.size() + "   sens " + String.format("%.2f", sensitivity) + (frozen ? "   frozen" : ""), 48, 100, p);
    }

    private boolean crosses(float a, float b, float level) {
        return (a < level && b >= level) || (b < level && a >= level);
    }

    private float interp(float a, float b, float level) {
        float d = b - a;
        if (Math.abs(d) < 0.00001f) return 0.5f;
        return clamp01((level - a) / d);
    }

    private void normalize(float[] data) {
        float max = 0f;
        for (float v : data) if (v > max) max = v;
        if (max < 0.0001f) return;

        for (int i = 0; i < data.length; i++) {
            data[i] = clamp01(data[i] / max);
        }
    }

    private float hash01(String s) {
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return (h & 0x7fffffff) / (float)0x7fffffff;
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
