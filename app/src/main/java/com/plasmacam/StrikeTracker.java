package com.plasmacam;

import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Optical-flow strike tracker.
 * Tracks fist/limb motion via frame-delta pixel centroids — no ML model needed.
 * Speed = pixel displacement / frame_time_ms, scaled to m/s via calibration factor.
 * Power = peak_speed * technique_multiplier (0.5–1.0).
 * Technique is scored by straightness of trajectory + acceleration profile.
 */
public class StrikeTracker {

    private static final String TAG = "StrikeTracker";

    // Pixels-per-meter at arm's length (~60cm from phone). Tunable.
    private static final float PPM = 420f;
    // Minimum motion threshold to register a strike candidate (pixels)
    private static final float MOTION_THRESHOLD_PX = 18f;
    // Minimum peak speed to log as a strike (m/s)
    private static final float MIN_STRIKE_SPEED_MS = 1.5f;
    // Frame window for strike detection
    private static final int WINDOW_FRAMES = 8;

    public static class StrikeResult {
        public float speedMs;          // m/s
        public float powerScore;       // 0–100
        public float techniqueScore;   // 0–100
        public long timestampMs;
        public boolean isPerfect;      // technique >= 85 AND speed > 7 m/s

        @Override
        public String toString() {
            return String.format("Speed:%.1fm/s Power:%.0f Tech:%.0f%s",
                    speedMs, powerScore, techniqueScore, isPerfect ? " ★PERFECT" : "");
        }
    }

    // Ring buffer of motion centroids
    private final PointF[] centroids = new PointF[WINDOW_FRAMES];
    private final long[] timestamps = new long[WINDOW_FRAMES];
    private int head = 0;
    private int count = 0;

    private StrikeResult lastStrike = null;
    private StrikeResult personalBestSpeed = null;
    private StrikeResult personalBestPower = null;
    private int totalStrikes = 0;

    private final StrikeDatabase db;
    private final StrikeListener listener;

    public interface StrikeListener {
        void onStrike(StrikeResult result, boolean isPersonalBest);
    }

    public StrikeTracker(StrikeDatabase db, StrikeListener listener) {
        this.db = db;
        this.listener = listener;
        for (int i = 0; i < WINDOW_FRAMES; i++) centroids[i] = new PointF(0, 0);
        loadPersonalBests();
    }

    private void loadPersonalBests() {
        if (db != null) {
            StrikeResult pbSpeed = db.getPersonalBest("speed");
            StrikeResult pbPower = db.getPersonalBest("power");
            if (pbSpeed != null) personalBestSpeed = pbSpeed;
            if (pbPower != null) personalBestPower = pbPower;
        }
    }

    /**
     * Feed each processed ARGB frame into this method.
     * Computes frame-delta motion centroid and updates strike detection.
     */
    public StrikeResult processFrame(int[] prevArgb, int[] currArgb, int w, int h) {
        if (prevArgb == null || currArgb == null) return null;

        PointF centroid = computeMotionCentroid(prevArgb, currArgb, w, h);
        long now = System.currentTimeMillis();

        centroids[head] = centroid;
        timestamps[head] = now;
        head = (head + 1) % WINDOW_FRAMES;
        if (count < WINDOW_FRAMES) count++;

        return detectStrike();
    }

    private PointF computeMotionCentroid(int[] prev, int[] curr, int w, int h) {
        float sumX = 0, sumY = 0, weight = 0;

        // Sample every 4th pixel for speed
        for (int y = 4; y < h - 4; y += 4) {
            for (int x = 4; x < w - 4; x += 4) {
                int idx = y * w + x;
                float diff = pixelDiff(prev[idx], curr[idx]);
                if (diff > MOTION_THRESHOLD_PX) {
                    sumX += x * diff;
                    sumY += y * diff;
                    weight += diff;
                }
            }
        }

        if (weight < 1f) return new PointF(0, 0);
        return new PointF(sumX / weight, sumY / weight);
    }

    private float pixelDiff(int a, int b) {
        int rA = (a >> 16) & 0xFF, gA = (a >> 8) & 0xFF, bA = a & 0xFF;
        int rB = (b >> 16) & 0xFF, gB = (b >> 8) & 0xFF, bB = b & 0xFF;
        return (Math.abs(rA - rB) + Math.abs(gA - gB) + Math.abs(bA - bB)) / 3f;
    }

    private StrikeResult detectStrike() {
        if (count < 3) return null;

        // Collect valid motion points in window
        List<PointF> pts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        float totalMotion = 0;

        for (int i = 0; i < count; i++) {
            int idx = ((head - 1 - i) + WINDOW_FRAMES) % WINDOW_FRAMES;
            PointF p = centroids[idx];
            if (p.x != 0 || p.y != 0) {
                pts.add(0, p);
                times.add(0, timestamps[idx]);
                if (pts.size() > 1) {
                    PointF prev = pts.get(pts.size() - 2);
                    totalMotion += dist(prev, p);
                }
            }
        }

        if (pts.size() < 2 || totalMotion < 5f) return null;

        // Compute peak speed over best consecutive pair
        float peakSpeedPx = 0;
        for (int i = 1; i < pts.size(); i++) {
            float d = dist(pts.get(i - 1), pts.get(i));
            long dt = times.get(i) - times.get(i - 1);
            if (dt > 0) {
                float spx = d / dt * 1000f; // px/s
                if (spx > peakSpeedPx) peakSpeedPx = spx;
            }
        }

        float speedMs = peakSpeedPx / PPM;
        if (speedMs < MIN_STRIKE_SPEED_MS) return null;

        float techniqueScore = scoreTechnique(pts);
        float powerScore = Math.min(100f, (speedMs / 15f) * 100f * (techniqueScore / 100f));

        StrikeResult result = new StrikeResult();
        result.speedMs = speedMs;
        result.powerScore = powerScore;
        result.techniqueScore = techniqueScore;
        result.timestampMs = System.currentTimeMillis();
        result.isPerfect = techniqueScore >= 85f && speedMs >= 7f;

        lastStrike = result;
        totalStrikes++;

        boolean isPB = false;
        if (personalBestSpeed == null || speedMs > personalBestSpeed.speedMs) {
            personalBestSpeed = result;
            isPB = true;
        }
        if (personalBestPower == null || powerScore > personalBestPower.powerScore) {
            personalBestPower = result;
            isPB = true;
        }

        if (db != null) db.saveStrike(result);
        if (listener != null) listener.onStrike(result, isPB);

        // Reset window after registering strike
        count = 0;
        return result;
    }

    /**
     * Technique score: measures how straight and explosive the trajectory is.
     * Straight line = high score. Erratic/circular = low score.
     */
    private float scoreTechnique(List<PointF> pts) {
        if (pts.size() < 2) return 50f;

        PointF start = pts.get(0);
        PointF end = pts.get(pts.size() - 1);
        float directDist = dist(start, end);
        float pathDist = 0;

        for (int i = 1; i < pts.size(); i++) {
            pathDist += dist(pts.get(i - 1), pts.get(i));
        }

        if (pathDist < 1f) return 50f;

        // Straightness ratio: 1.0 = perfect line
        float straightness = directDist / pathDist;

        // Acceleration check: speed should increase toward impact
        float accelBonus = 0;
        if (pts.size() >= 4) {
            float earlySpeed = dist(pts.get(0), pts.get(1));
            float lateSpeed = dist(pts.get(pts.size() - 2), pts.get(pts.size() - 1));
            if (lateSpeed > earlySpeed) accelBonus = 15f;
        }

        return Math.min(100f, straightness * 85f + accelBonus);
    }

    private float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public StrikeResult getLastStrike() { return lastStrike; }
    public StrikeResult getPersonalBestSpeed() { return personalBestSpeed; }
    public StrikeResult getPersonalBestPower() { return personalBestPower; }
    public int getTotalStrikes() { return totalStrikes; }
}
