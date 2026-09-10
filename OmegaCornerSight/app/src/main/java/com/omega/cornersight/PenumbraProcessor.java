package com.omega.cornersight;

import android.graphics.RectF;

import java.util.Arrays;

public final class PenumbraProcessor {
    public static final int BINS = 64;
    public static final int CAL_FRAMES = 90;

    public static final class Result {
        public final float[] row;
        public final float angleDeg;
        public final float angularSpeed;
        public final float confidence;
        public final int clusters;
        public final boolean calibrating;
        public final int calibrationFrame;
        public final boolean frozen;
        public final float relX;
        public final float relY;

        Result(float[] row, float angleDeg, float angularSpeed, float confidence, int clusters,
               boolean calibrating, int calibrationFrame, boolean frozen, float relX, float relY) {
            this.row = row;
            this.angleDeg = angleDeg;
            this.angularSpeed = angularSpeed;
            this.confidence = confidence;
            this.clusters = clusters;
            this.calibrating = calibrating;
            this.calibrationFrame = calibrationFrame;
            this.frozen = frozen;
            this.relX = relX;
            this.relY = relY;
        }
    }

    private final float[] mean = new float[BINS];
    private final float[] m2 = new float[BINS];
    private final float[] sigma = new float[BINS];
    private final float[] smooth = new float[BINS];
    private int nCal = 0;
    private boolean calibrating = true;
    private long lastNs = 0L;
    private float lastAngle = 0f;
    private float gain = 1.0f;

    public void setGain(float g) {
        gain = Math.max(0.5f, Math.min(4f, g));
    }

    public void startCalibration() {
        Arrays.fill(mean, 0f);
        Arrays.fill(m2, 0f);
        Arrays.fill(sigma, 1f);
        Arrays.fill(smooth, 0f);
        nCal = 0;
        calibrating = true;
        lastNs = 0L;
        lastAngle = 0f;
    }

    public Result process(byte[] y, int width, int height, int rowStride, RectF roi,
                          boolean stable, boolean dualMode, long nowNs) {
        if (!stable) {
            return new Result(new float[BINS], lastAngle, 0f, 0f, 0,
                    calibrating, nCal, true, 0f, 0f);
        }

        int x0 = clamp((int) (roi.left * width), 0, width - 2);
        int x1 = clamp((int) (roi.right * width), x0 + 1, width);
        int y0 = clamp((int) (roi.top * height), 0, height - 2);
        int y1 = clamp((int) (roi.bottom * height), y0 + 1, height);

        float[] raw = new float[BINS];
        for (int b = 0; b < BINS; b++) {
            int bx0 = x0 + (x1 - x0) * b / BINS;
            int bx1 = x0 + (x1 - x0) * (b + 1) / BINS;
            bx1 = Math.max(bx0 + 1, bx1);
            long sum = 0;
            int count = 0;
            int sx = Math.max(1, (bx1 - bx0) / 3);
            int sy = Math.max(1, (y1 - y0) / 24);
            for (int py = y0; py < y1; py += sy) {
                int base = py * rowStride;
                for (int px = bx0; px < bx1; px += sx) {
                    int idx = base + px;
                    if (idx >= 0 && idx < y.length) {
                        sum += y[idx] & 0xff;
                        count++;
                    }
                }
            }
            raw[b] = count == 0 ? 0f : (float) sum / count;
        }

        float global = median(raw);
        for (int i = 0; i < BINS; i++) raw[i] -= global;

        if (calibrating) {
            nCal++;
            for (int i = 0; i < BINS; i++) {
                float delta = raw[i] - mean[i];
                mean[i] += delta / nCal;
                float delta2 = raw[i] - mean[i];
                m2[i] += delta * delta2;
            }
            if (nCal >= CAL_FRAMES) {
                calibrating = false;
                for (int i = 0; i < BINS; i++) {
                    float variance = m2[i] / Math.max(1, nCal - 1);
                    sigma[i] = Math.max(0.20f, (float) Math.sqrt(variance));
                }
            }
            return new Result(new float[BINS], 0f, 0f, 0f, 0,
                    true, nCal, false, 0f, 0f);
        }

        float[] activity = new float[BINS];
        float max = 0f;
        for (int i = 0; i < BINS; i++) {
            float z = (raw[i] - mean[i]) / sigma[i];
            if (Math.abs(z) < 1.35f) mean[i] = mean[i] * 0.9995f + raw[i] * 0.0005f;
            smooth[i] = 0.70f * smooth[i] + 0.30f * z;
            activity[i] = Math.min(10f, Math.abs(smooth[i]) * gain);
            max = Math.max(max, activity[i]);
        }

        float weighted = 0f;
        float weights = 0f;
        int clusters = 0;
        boolean inside = false;
        for (int i = 0; i < BINS; i++) {
            float w = Math.max(0f, activity[i] - 2.0f);
            if (w > 0f) {
                weighted += i * w;
                weights += w;
                if (!inside) clusters++;
                inside = true;
            } else {
                inside = false;
            }
        }

        float angle = lastAngle;
        float conf = Math.min(1f, Math.max(0f, (max - 2.0f) / 5.0f));
        if (weights > 0f) {
            float centroid = weighted / weights;
            angle = -70f + 140f * centroid / (BINS - 1f);
        }

        float speed = 0f;
        if (lastNs != 0L && weights > 0f) {
            float dt = (nowNs - lastNs) * 1e-9f;
            if (dt > 0.005f && dt < 1f) speed = (angle - lastAngle) / dt;
        }
        if (weights > 0f) {
            lastAngle = angle;
            lastNs = nowNs;
        }

        float relX = 0f;
        float relY = 0f;
        if (dualMode) {
            float[] lr = halfCentroids(activity);
            float l = lr[0];
            float r = lr[1];
            relX = clampf((l + r) * 0.5f, -1f, 1f);
            relY = clampf(1f - Math.abs(l - r) * 0.55f, 0f, 1f);
        }

        float[] row = new float[BINS];
        for (int i = 0; i < BINS; i++) row[i] = Math.min(1f, activity[i] / 7f);
        return new Result(row, angle, speed, conf, clusters,
                false, nCal, false, relX, relY);
    }

    private static float[] halfCentroids(float[] a) {
        float wl = 0f, sl = 0f, wr = 0f, sr = 0f;
        int half = BINS / 2;
        for (int i = 0; i < half; i++) {
            float w = Math.max(0f, a[i] - 1.8f);
            wl += w;
            sl += w * i;
        }
        for (int i = half; i < BINS; i++) {
            float w = Math.max(0f, a[i] - 1.8f);
            wr += w;
            sr += w * (i - half);
        }
        float l = wl > 0f ? (sl / wl) / (half - 1f) * 2f - 1f : 0f;
        float r = wr > 0f ? (sr / wr) / (half - 1f) * 2f - 1f : 0f;
        return new float[]{l, r};
    }

    private static float median(float[] src) {
        float[] c = src.clone();
        Arrays.sort(c);
        return 0.5f * (c[c.length / 2 - 1] + c[c.length / 2]);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampf(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
