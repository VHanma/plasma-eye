package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.wifi.ScanResult;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SensorFieldView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float magX = 0f;
    private float magY = 0f;
    private float magZ = 0f;
    private float magAbs = 0f;
    private int magAccuracy = 0;

    private float accelAbs = 0f;
    private float gyroAbs = 0f;
    private float lux = -1f;

    private final List<ScanResult> wifi = new ArrayList<>();

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
        p.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void setMagnetic(float x, float y, float z, int accuracy) {
        magX = x;
        magY = y;
        magZ = z;
        magAccuracy = accuracy;
        magAbs = (float)Math.sqrt(x * x + y * y + z * z);
        postInvalidate();
    }

    public void setAccel(float x, float y, float z) {
        accelAbs = (float)Math.sqrt(x * x + y * y + z * z);
        postInvalidate();
    }

    public void setGyro(float x, float y, float z) {
        gyroAbs = (float)Math.sqrt(x * x + y * y + z * z);
        postInvalidate();
    }

    public void setLux(float value) {
        lux = value;
        postInvalidate();
    }

    public void setWifiResults(List<ScanResult> results) {
        wifi.clear();
        if (results != null) {
            ArrayList<ScanResult> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.comparingInt(a -> -a.level));
            for (int i = 0; i < Math.min(8, sorted.size()); i++) {
                wifi.add(sorted.get(i));
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

        drawMagneticField(c, w, h);
        drawWifiRadar(c, w, h);
        drawReadout(c, w, h);
    }

    private void drawMagneticField(Canvas c, int w, int h) {
        float earthMid = 50f;
        float fieldStrength = clamp01(magAbs / 150f);
        float anomaly = clamp01(Math.abs(magAbs - earthMid) / 100f);

        int alpha = 45 + (int)(150 * fieldStrength);
        p.setStrokeWidth(2f + 5f * anomaly);
        p.setColor(android.graphics.Color.argb(alpha, 0, 255, 210));

        float baseAngle = (float)Math.atan2(magY, magX);
        float cell = Math.max(48f, w / 9f);

        for (float y = cell; y < h - cell; y += cell) {
            for (float x = cell; x < w - cell; x += cell) {
                float swirl = (float)Math.sin((x * 0.015f) + (y * 0.009f) + magZ * 0.04f);
                float angle = baseAngle + swirl * 0.9f;

                float len = cell * (0.25f + fieldStrength * 0.85f);
                float x2 = x + (float)Math.cos(angle) * len;
                float y2 = y + (float)Math.sin(angle) * len;

                c.drawLine(x, y, x2, y2, p);

                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x2, y2, 3f + 7f * anomaly, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(android.graphics.Color.argb(110 + (int)(100 * anomaly), 255, 80, 40));
        float radius = 40f + anomaly * 190f;
        c.drawCircle(w * 0.5f, h * 0.45f, radius, p);
        c.drawCircle(w * 0.5f, h * 0.45f, radius * 0.55f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawWifiRadar(Canvas c, int w, int h) {
        float cx = w - 130f;
        float cy = 150f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(android.graphics.Color.argb(80, 80, 180, 255));
        c.drawCircle(cx, cy, 45f, p);
        c.drawCircle(cx, cy, 85f, p);
        c.drawCircle(cx, cy, 125f, p);

        p.setStyle(Paint.Style.FILL);
        p.setTextSize(20f);
        p.setColor(android.graphics.Color.argb(220, 180, 220, 255));
        c.drawText("Wi-Fi", cx - 28f, cy - 135f, p);

        for (int i = 0; i < wifi.size(); i++) {
            ScanResult r = wifi.get(i);

            float strength = clamp01((r.level + 100f) / 60f);
            float angle = (float)(i * Math.PI * 2.0 / Math.max(1, wifi.size())) + magX * 0.01f;
            float dist = 125f - strength * 90f;

            float x = cx + (float)Math.cos(angle) * dist;
            float y = cy + (float)Math.sin(angle) * dist;

            int alpha = 90 + (int)(165 * strength);
            p.setColor(android.graphics.Color.argb(alpha, 255, 190, 40));
            c.drawCircle(x, y, 5f + strength * 16f, p);

            p.setTextSize(16f);
            p.setColor(android.graphics.Color.argb(alpha, 255, 240, 170));

            String ssid = r.SSID == null || r.SSID.length() == 0 ? "<hidden>" : r.SSID;
            if (ssid.length() > 10) ssid = ssid.substring(0, 10);
            c.drawText(ssid + " " + r.level, x + 10f, y, p);
        }

        p.setStyle(Paint.Style.FILL);
    }

    private void drawReadout(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(android.graphics.Color.argb(150, 0, 0, 0));
        c.drawRect(0, 0, w, 118f, p);

        p.setTextSize(24f);
        p.setColor(android.graphics.Color.argb(240, 0, 255, 210));

        String line1 = String.format(
                Locale.US,
                "MAG %.1f µT   X %.1f  Y %.1f  Z %.1f",
                magAbs, magX, magY, magZ
        );

        String line2 = String.format(
                Locale.US,
                "ACC %.2f m/s²   GYRO %.2f rad/s   LUX %s   WIFI %d",
                accelAbs,
                gyroAbs,
                lux >= 0 ? String.format(Locale.US, "%.1f", lux) : "n/a",
                wifi.size()
        );

        c.drawText(line1, 18f, 38f, p);

        p.setTextSize(20f);
        p.setColor(android.graphics.Color.argb(230, 255, 220, 110));
        c.drawText(line2, 18f, 72f, p);

        p.setTextSize(17f);
        p.setColor(android.graphics.Color.argb(220, 180, 180, 180));
        c.drawText("Accuracy " + magAccuracy + " | move phone through space to map changes", 18f, 101f, p);
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
