package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.wifi.ScanResult;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final int LAYER_SPECTER = 0;
    private static final int LAYER_WIFI = 1;
    private static final int LAYER_BLE = 2;
    private static final int LAYER_MAG = 3;
    private static final int LAYER_PING = 4;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<ScanResult> wifi = new ArrayList<>();
    private final List<BleSignal> ble = new ArrayList<>();

    private final Map<String, Integer> wifiBaseline = new HashMap<>();
    private final Map<String, Integer> bleBaseline = new HashMap<>();

    private float magX, magY, magZ, magAbs;
    private float baselineMag = 50f;

    private float accelAbs;
    private float gyroAbs;
    private float lux = -1f;

    private int layer = LAYER_SPECTER;
    private float sensitivity = 1.0f;

    private boolean calibrated = false;
    private boolean frozen = false;

    private float[] memoryField;
    private float[] frozenField;
    private int memoryCols = 0;
    private int memoryRows = 0;

    private long lastTap = 0L;
    private long flashUntil = 0L;
    private float touchDownY = 0f;

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
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = System.currentTimeMillis();

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownY = e.getY();
            return true;
        }

        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dy = e.getY() - touchDownY;

            if (Math.abs(dy) > 90f) {
                if (dy < 0) sensitivity = Math.min(2.8f, sensitivity + 0.18f);
                else sensitivity = Math.max(0.25f, sensitivity - 0.18f);

                flashUntil = now + 850L;
                invalidate();
                return true;
            }

            if (now - lastTap < 330L) {
                frozen = !frozen;

                if (frozen && memoryField != null) {
                    frozenField = memoryField.clone();
                }

                flashUntil = now + 850L;
                lastTap = 0L;
                invalidate();
                return true;
            }

            lastTap = now;
            postDelayed(() -> {
                if (System.currentTimeMillis() - lastTap >= 320L) {
                    PlasmaEngine.nextFilter();
                    flashUntil = System.currentTimeMillis() + 850L;
                    invalidate();
                }
            }, 340);

            return true;
        }

        return true;
    }

    @Override
    public boolean performLongClick() {
        calibrateBaseline();
        return super.performLongClick();
    }

    private void calibrateBaseline() {
        wifiBaseline.clear();
        bleBaseline.clear();

        for (ScanResult r : wifi) {
            wifiBaseline.put(wifiKey(r), r.level);
        }

        for (BleSignal b : ble) {
            bleBaseline.put(bleKey(b), b.rssi);
        }

        baselineMag = magAbs > 1f ? magAbs : 50f;
        calibrated = true;
        flashUntil = System.currentTimeMillis() + 1200L;

        if (memoryField != null) {
            for (int i = 0; i < memoryField.length; i++) memoryField[i] = 0f;
        }

        frozen = false;
        frozenField = null;
        invalidate();
    }

    public void setMagnetic(float x, float y, float z, int accuracy) {
        magX = x;
        magY = y;
        magZ = z;
        magAbs = (float)Math.sqrt(x * x + y * y + z * z);
        postInvalidate();
    }

    public void setAccel(float x, float y, float z) {
        accelAbs = (float)Math.sqrt(x * x + y * y + z * z);
    }

    public void setGyro(float x, float y, float z) {
        gyroAbs = (float)Math.sqrt(x * x + y * y + z * z);
    }

    public void setLux(float value) {
        lux = value;
    }

    public void setWifiResults(List<ScanResult> results) {
        wifi.clear();

        if (results != null) {
            ArrayList<ScanResult> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingInt(a -> -a.level));

            for (int i = 0; i < Math.min(40, sorted.size()); i++) {
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
                if (now - b.lastSeen < 30000L && ble.size() < 56) {
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

        int cols = 72;
        int rows = Math.max(40, (int)(cols * (h / (float)w)));

        ensureMemory(cols, rows);

        float[] wifiField = new float[cols * rows];
        float[] bleField = new float[cols * rows];
        float[] magField = new float[cols * rows];
        float[] pingField = new float[cols * rows];
        float[] composite = new float[cols * rows];

        buildWifiDeviationField(wifiField, cols, rows);
        buildBleDeviationField(bleField, cols, rows);
        buildMagneticDeviationField(magField, cols, rows);
        buildPingReturnField(pingField, cols, rows, wifiField, bleField, magField);

        for (int i = 0; i < composite.length; i++) {
            composite[i] = clamp01(
                    wifiField[i] * 0.90f +
                    bleField[i] * 0.90f +
                    magField[i] * 1.15f +
                    pingField[i] * 0.75f
            );
        }

        float[] active;

        if (layer == LAYER_WIFI) active = wifiField;
        else if (layer == LAYER_BLE) active = bleField;
        else if (layer == LAYER_MAG) active = magField;
        else if (layer == LAYER_PING) active = pingField;
        else active = composite;

        if (!frozen) updateMemory(active);

        float[] display = frozen && frozenField != null ? frozenField : memoryField;

        drawCleanContours(c, display, cols, rows, w, h);
        drawSpecterPingRings(c, display, cols, rows, w, h);

        if (layer == LAYER_MAG || layer == LAYER_SPECTER) {
            drawMagneticVectors(c, w, h);
        }

        if (System.currentTimeMillis() < flashUntil) {
            drawBriefStatus(c, w, h);
        }

        postInvalidateDelayed(33);
    }

    private void ensureMemory(int cols, int rows) {
        if (memoryField == null || memoryCols != cols || memoryRows != rows) {
            memoryCols = cols;
            memoryRows = rows;
            memoryField = new float[cols * rows];
        }
    }

    private void updateMemory(float[] active) {
        if (memoryField == null || memoryField.length != active.length) return;

        for (int i = 0; i < active.length; i++) {
            float incoming = active[i];

            if (incoming > memoryField[i]) {
                memoryField[i] = memoryField[i] * 0.60f + incoming * 0.40f;
            } else {
                memoryField[i] *= 0.986f;
            }

            if (memoryField[i] < 0.012f) memoryField[i] = 0f;
            if (memoryField[i] > 1f) memoryField[i] = 1f;
        }
    }

    private void buildWifiDeviationField(float[] out, int cols, int rows) {
        for (ScanResult r : wifi) {
            String key = wifiKey(r);

            float strength = rssiToStrength(r.level);
            float baseline = wifiBaseline.containsKey(key)
                    ? rssiToStrength(wifiBaseline.get(key))
                    : 0.0f;

            float delta = calibrated ? Math.abs(strength - baseline) : strength * 0.45f;
            delta *= sensitivity;

            if (r.frequency >= 5000) delta *= 1.10f;

            addHardSource(out, cols, rows, key, delta, 0.105f);
        }

        normalize(out);
    }

    private void buildBleDeviationField(float[] out, int cols, int rows) {
        long now = System.currentTimeMillis();

        for (BleSignal b : ble) {
            String key = bleKey(b);

            float strength = rssiToStrength(b.rssi);
            float baseline = bleBaseline.containsKey(key)
                    ? rssiToStrength(bleBaseline.get(key))
                    : 0.0f;

            float age = clamp01(1f - ((now - b.lastSeen) / 30000f));
            float delta = calibrated ? Math.abs(strength - baseline) : strength * 0.50f;
            delta *= age * sensitivity;

            addHardSource(out, cols, rows, key, delta, 0.078f);
        }

        normalize(out);
    }

    private void buildMagneticDeviationField(float[] out, int cols, int rows) {
        float anomaly = clamp01(Math.abs(magAbs - baselineMag) / 65f) * sensitivity;
        float angle = (float)Math.atan2(magY, magX);

        float sx = 0.50f + 0.30f * (float)Math.cos(angle);
        float sy = 0.50f + 0.30f * (float)Math.sin(angle);

        addGaussian(out, cols, rows, sx, sy, anomaly * 1.35f, 0.145f);

        float sx2 = 0.50f - 0.30f * (float)Math.cos(angle);
        float sy2 = 0.50f - 0.30f * (float)Math.sin(angle);

        addGaussian(out, cols, rows, sx2, sy2, anomaly * 0.85f, 0.115f);

        float waveAngle = angle + magZ * 0.01f;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float nx = x / (float)(cols - 1);
                float ny = y / (float)(rows - 1);

                float line = (float)Math.sin((nx * Math.cos(waveAngle) + ny * Math.sin(waveAngle)) * 34f);
                out[y * cols + x] += anomaly * Math.abs(line) * 0.42f;
            }
        }

        normalize(out);
    }

    private void buildPingReturnField(
            float[] out,
            int cols,
            int rows,
            float[] wifiField,
            float[] bleField,
            float[] magField
    ) {
        long now = System.currentTimeMillis();
        float phase = (now % 2600L) / 2600f;

        float cx = 0.5f;
        float cy = 0.5f;
        float radius = phase * 0.95f;
        float width = 0.035f;

        for (int y = 0; y < rows; y++) {
            float ny = y / (float)(rows - 1);

            for (int x = 0; x < cols; x++) {
                float nx = x / (float)(cols - 1);

                float dx = nx - cx;
                float dy = ny - cy;
                float d = (float)Math.sqrt(dx * dx + dy * dy);

                float ring = 1f - clamp01(Math.abs(d - radius) / width);
                int i = y * cols + x;

                float returnSignal =
                        wifiField[i] * 0.85f +
                        bleField[i] * 0.85f +
                        magField[i] * 1.15f;

                out[i] = clamp01(ring * returnSignal * 1.35f);
            }
        }

        normalize(out);
    }

    private void addHardSource(float[] out, int cols, int rows, String key, float strength, float radius) {
        if (strength <= 0.015f) return;

        float sx = hash01(key + ":x");
        float sy = hash01(key + ":y");

        addGaussian(out, cols, rows, sx, sy, strength, radius);

        float sx2 = clamp01(sx + (hash01(key + ":mirrorx") - 0.5f) * 0.18f);
        float sy2 = clamp01(sy + (hash01(key + ":mirrory") - 0.5f) * 0.18f);

        addGaussian(out, cols, rows, sx2, sy2, strength * 0.55f, radius * 0.72f);
    }

    private void addGaussian(float[] out, int cols, int rows, float sx, float sy, float strength, float radius) {
        if (strength <= 0.001f) return;

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

    private void drawCleanContours(Canvas c, float[] field, int cols, int rows, int w, int h) {
        int primary;
        int secondary;
        int tertiary;

        if (layer == LAYER_WIFI) {
            primary = Color.argb(255, 30, 190, 255);
            secondary = Color.argb(245, 255, 255, 255);
            tertiary = Color.argb(230, 0, 255, 190);
        } else if (layer == LAYER_BLE) {
            primary = Color.argb(255, 190, 40, 255);
            secondary = Color.argb(245, 255, 255, 255);
            tertiary = Color.argb(230, 255, 80, 220);
        } else if (layer == LAYER_MAG) {
            primary = Color.argb(255, 0, 255, 115);
            secondary = Color.argb(245, 255, 255, 255);
            tertiary = Color.argb(230, 255, 230, 70);
        } else if (layer == LAYER_PING) {
            primary = Color.argb(255, 255, 255, 255);
            secondary = Color.argb(245, 255, 160, 40);
            tertiary = Color.argb(230, 0, 255, 220);
        } else {
            primary = Color.argb(255, 255, 255, 255);
            secondary = Color.argb(245, 0, 255, 210);
            tertiary = Color.argb(235, 255, 85, 40);
        }

        drawContourLevel(c, field, cols, rows, w, h, 0.34f, primary, 5.8f);
        drawContourLevel(c, field, cols, rows, w, h, 0.50f, secondary, 3.7f);
        drawContourLevel(c, field, cols, rows, w, h, 0.67f, tertiary, 2.5f);
        drawContourLevel(c, field, cols, rows, w, h, 0.82f, Color.argb(245, 255, 255, 255), 1.5f);
    }

    private void drawContourLevel(
            Canvas c,
            float[] field,
            int cols,
            int rows,
            int screenW,
            int screenH,
            float level,
            int color,
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

    private void drawSpecterPingRings(Canvas c, float[] field, int cols, int rows, int w, int h) {
        long now = System.currentTimeMillis();
        float phase = (now % 2600L) / 2600f;

        p.setStyle(Paint.Style.STROKE);

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float maxR = (float)Math.sqrt(w * w + h * h) * 0.55f;

        for (int i = 0; i < 3; i++) {
            float local = (phase + i * 0.333f) % 1f;
            float r = local * maxR;

            int alpha = (int)(185 * (1f - local));
            p.setStrokeWidth(2.0f + 2.0f * (1f - local));
            p.setColor(Color.argb(clamp(alpha, 0, 190), 255, 255, 255));
            c.drawCircle(cx, cy, r, p);
        }

        float strongest = strongest(field);
        if (strongest > 0.45f) {
            p.setStrokeWidth(7f);
            p.setColor(Color.argb((int)(120 * strongest), 255, 255, 255));
            c.drawRect(10, 10, w - 10, h - 10, p);
        }
    }

    private void drawMagneticVectors(Canvas c, int w, int h) {
        float anomaly = clamp01(Math.abs(magAbs - baselineMag) / 65f) * sensitivity;
        if (anomaly < 0.03f) return;

        float angle = (float)Math.atan2(magY, magX);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.0f + anomaly * 5f);
        p.setColor(Color.argb(210, 0, 255, 120));

        int count = 16;

        for (int i = 0; i < count; i++) {
            float t = i / (float)(count - 1);
            float x = t * w;
            float y = h * 0.5f + (float)Math.sin(t * Math.PI * 2f + magZ * 0.025f) * h * 0.20f;

            float len = w * (0.14f + anomaly * 0.28f);

            float x2 = x + (float)Math.cos(angle) * len;
            float y2 = y + (float)Math.sin(angle) * len;

            c.drawLine(x, y, x2, y2, p);
        }
    }

    private void drawBriefStatus(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(170, 0, 0, 0));
        c.drawRoundRect(24, 24, w - 24, 142, 18, 18, p);

        p.setTextSize(30f);
        p.setColor(Color.argb(255, 255, 255, 255));
        c.drawText(PlasmaEngine.filterName(), 48, 66, p);

        p.setTextSize(20f);
        p.setColor(Color.argb(245, 0, 255, 210));

        String state =
                (calibrated ? "BASELINE LOCKED" : "LONG PRESS TO BASELINE")
                + "  |  SENS " + String.format("%.2f", sensitivity)
                + (frozen ? "  |  FROZEN" : "");

        c.drawText(state, 48, 102, p);
    }

    private String layerName() {
        switch (layer) {
            case LAYER_WIFI: return "WIFI CONTOUR";
            case LAYER_BLE: return "BLUETOOTH CONTOUR";
            case LAYER_MAG: return "MAGNETIC CONTOUR";
            case LAYER_PING: return "SPECTER PING";
            case LAYER_SPECTER:
            default: return "SPECTER COMPOSITE";
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

    private float strongest(float[] data) {
        float max = 0f;
        if (data == null) return 0f;

        for (float v : data) {
            if (v > max) max = v;
        }

        return clamp01(max);
    }

    private String wifiKey(ScanResult r) {
        return safe(r.BSSID) + "|" + safe(r.SSID) + "|" + r.frequency;
    }

    private String bleKey(BleSignal b) {
        return safe(b.address).length() > 0 ? safe(b.address) : safe(b.name);
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

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
