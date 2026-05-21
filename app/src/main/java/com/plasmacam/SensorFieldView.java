package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.wifi.ScanResult;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class SensorFieldView extends View {
    public static class BleSignal {
        public final String name;
        public final String address;
        public final int rssi;
        public final long lastSeen;

        public BleSignal(String name, String address, int rssi, long lastSeen) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
            this.rssi = rssi;
            this.lastSeen = lastSeen;
        }
    }

    private static final int LAYER_COMPOSITE = 0;
    private static final int LAYER_WIFI = 1;
    private static final int LAYER_BLE = 2;
    private static final int LAYER_MAG = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<ScanResult> wifi = new ArrayList<>();
    private final List<BleSignal> ble = new ArrayList<>();

    private float magX, magY, magZ, magAbs;
    private float baselineMag = 50f;
    private int layer = LAYER_COMPOSITE;

    private long calibrationFlashUntil = 0L;

    public SensorFieldView(Context c) {
        super(c);
        init();
    }

    public SensorFieldView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        setOnClickListener(v -> {
            layer = (layer + 1) % 4;
            invalidate();
        });

        setOnLongClickListener(v -> {
            baselineMag = magAbs > 1f ? magAbs : 50f;
            calibrationFlashUntil = System.currentTimeMillis() + 700L;
            invalidate();
            return true;
        });
    }

    public void setMagnetic(float x, float y, float z, int accuracy) {
        magX = x;
        magY = y;
        magZ = z;
        magAbs = (float)Math.sqrt(x * x + y * y + z * z);
        postInvalidate();
    }

    public void setAccel(float x, float y, float z) {
        // Kept for MainActivity compatibility.
    }

    public void setGyro(float x, float y, float z) {
        // Kept for MainActivity compatibility.
    }

    public void setLux(float value) {
        // Kept for MainActivity compatibility.
    }

    public void setWifiResults(List<ScanResult> results) {
        wifi.clear();

        if (results != null) {
            ArrayList<ScanResult> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingInt(a -> -a.level));

            for (int i = 0; i < Math.min(32, sorted.size()); i++) {
                wifi.add(sorted.get(i));
            }
        }

        postInvalidate();
    }

    public void setBleResults(Collection<BleSignal> results) {
        ble.clear();

        if (results != null) {
            long now = System.currentTimeMillis();
            ArrayList<BleSignal> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingInt(a -> -a.rssi));

            for (BleSignal b : sorted) {
                if (now - b.lastSeen < 30000L && ble.size() < 48) {
                    ble.add(b);
                }
            }
        }

        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        int w = getWidth();
        int h = getHeight();

        if (w <= 0 || h <= 0) return;

        int cols = 52;
        int rows = Math.max(28, (int)(cols * (h / (float)w)));

        float[] wifiField = new float[cols * rows];
        float[] bleField = new float[cols * rows];
        float[] magField = new float[cols * rows];
        float[] composite = new float[cols * rows];

        buildWifiField(wifiField, cols, rows);
        buildBleField(bleField, cols, rows);
        buildMagneticField(magField, cols, rows);

        for (int i = 0; i < composite.length; i++) {
            composite[i] = clamp01(wifiField[i] * 0.9f + bleField[i] * 0.9f + magField[i] * 1.15f);
        }

        if (layer == LAYER_COMPOSITE) {
            drawContours(c, composite, cols, rows, w, h, Color.argb(240, 255, 255, 255), 0.42f, 5.2f);
            drawContours(c, composite, cols, rows, w, h, Color.argb(220, 0, 255, 210), 0.58f, 3.4f);
            drawContours(c, composite, cols, rows, w, h, Color.argb(230, 255, 90, 40), 0.74f, 2.5f);
        } else if (layer == LAYER_WIFI) {
            drawContours(c, wifiField, cols, rows, w, h, Color.argb(245, 40, 180, 255), 0.34f, 5.0f);
            drawContours(c, wifiField, cols, rows, w, h, Color.argb(230, 255, 255, 255), 0.55f, 2.8f);
        } else if (layer == LAYER_BLE) {
            drawContours(c, bleField, cols, rows, w, h, Color.argb(245, 190, 40, 255), 0.34f, 5.0f);
            drawContours(c, bleField, cols, rows, w, h, Color.argb(230, 255, 255, 255), 0.55f, 2.8f);
        } else if (layer == LAYER_MAG) {
            drawContours(c, magField, cols, rows, w, h, Color.argb(245, 0, 255, 120), 0.30f, 5.0f);
            drawContours(c, magField, cols, rows, w, h, Color.argb(240, 255, 255, 255), 0.52f, 3.0f);
            drawMagneticHardLines(c, w, h);
        }

        if (System.currentTimeMillis() < calibrationFlashUntil) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(8f);
            p.setColor(Color.argb(220, 255, 255, 255));
            c.drawRect(12, 12, w - 12, h - 12, p);
        }
    }

    private void buildWifiField(float[] out, int cols, int rows) {
        for (ScanResult r : wifi) {
            String id = safe(r.BSSID) + safe(r.SSID);
            float sx = hash01(id + "x");
            float sy = hash01(id + "y");

            float strength = rssiToStrength(r.level);
            float freqBias = r.frequency >= 5000 ? 1.15f : 1.0f;

            addSource(out, cols, rows, sx, sy, strength * freqBias, 0.15f);
        }

        normalize(out);
    }

    private void buildBleField(float[] out, int cols, int rows) {
        long now = System.currentTimeMillis();

        for (BleSignal b : ble) {
            String id = safe(b.address) + safe(b.name);
            float sx = hash01(id + "x");
            float sy = hash01(id + "y");

            float age = clamp01(1f - ((now - b.lastSeen) / 30000f));
            float strength = rssiToStrength(b.rssi) * age;

            addSource(out, cols, rows, sx, sy, strength, 0.10f);
        }

        normalize(out);
    }

    private void buildMagneticField(float[] out, int cols, int rows) {
        float anomaly = clamp01(Math.abs(magAbs - baselineMag) / 80f);
        float angle = (float)Math.atan2(magY, magX);

        float cx = 0.5f + 0.25f * (float)Math.cos(angle);
        float cy = 0.5f + 0.25f * (float)Math.sin(angle);

        addSource(out, cols, rows, cx, cy, 0.25f + anomaly * 1.4f, 0.18f);

        float cx2 = 0.5f - 0.25f * (float)Math.cos(angle);
        float cy2 = 0.5f - 0.25f * (float)Math.sin(angle);

        addSource(out, cols, rows, cx2, cy2, anomaly * 0.9f, 0.14f);

        float waveAngle = angle + magZ * 0.01f;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float nx = x / (float)(cols - 1);
                float ny = y / (float)(rows - 1);

                float line = (float)Math.sin((nx * Math.cos(waveAngle) + ny * Math.sin(waveAngle)) * 28f);
                out[y * cols + x] += anomaly * Math.abs(line) * 0.45f;
            }
        }

        normalize(out);
    }

    private void addSource(float[] out, int cols, int rows, float sx, float sy, float strength, float radius) {
        if (strength <= 0.01f) return;

        float r2 = radius * radius;

        for (int y = 0; y < rows; y++) {
            float ny = y / (float)(rows - 1);

            for (int x = 0; x < cols; x++) {
                float nx = x / (float)(cols - 1);

                float dx = nx - sx;
                float dy = ny - sy;
                float d2 = dx * dx + dy * dy;

                float v = strength * (float)Math.exp(-d2 / Math.max(0.0001f, r2));
                out[y * cols + x] += v;
            }
        }
    }

    private void drawContours(
            Canvas c,
            float[] field,
            int cols,
            int rows,
            int screenW,
            int screenH,
            int color,
            float level,
            float stroke
    ) {
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

    private void drawMagneticHardLines(Canvas c, int w, int h) {
        float anomaly = clamp01(Math.abs(magAbs - baselineMag) / 80f);
        if (anomaly < 0.04f) return;

        float angle = (float)Math.atan2(magY, magX);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.4f + anomaly * 4f);
        p.setColor(Color.argb(190, 0, 255, 120));

        int count = 18;

        for (int i = 0; i < count; i++) {
            float t = i / (float)(count - 1);
            float x = t * w;
            float y = h * 0.5f + (float)Math.sin(t * Math.PI * 2f + magZ * 0.025f) * h * 0.22f;

            float len = w * (0.18f + anomaly * 0.35f);

            float x2 = x + (float)Math.cos(angle) * len;
            float y2 = y + (float)Math.sin(angle) * len;

            c.drawLine(x, y, x2, y2, p);
        }
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

        for (float v : data) {
            if (v > max) max = v;
        }

        if (max < 0.0001f) return;

        for (int i = 0; i < data.length; i++) {
            data[i] = clamp01(data[i] / max);
        }
    }

    private float rssiToStrength(int rssi) {
        return clamp01((rssi + 105f) / 65f);
    }

    private float hash01(String s) {
        int h = 0x811C9DC5;

        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }

        return (h & 0x7fffffff) / (float)0x7fffffff;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
