package com.vaan.behindthecurtain.session;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Locale;

public final class VisualAnalyzer {
    public static final class Result {
        public boolean save;
        public String kind;
        public double lowContrastEdgeRatio;
        public double strongEdgeRatio;
        public double temporalFlash;
        public Bitmap trace;
        public Bitmap reveal;

        public String note() {
            return String.format(Locale.US,
                    "candidate=%s\nlow_contrast_edge_ratio=%.5f\nstrong_edge_ratio=%.5f\ntemporal_change=%.3f\n" +
                    "trace=white lines emphasize faint edges; gray lines preserve stronger context\n" +
                    "reveal=contrast-stretched version of the same captured pixels\n",
                    kind, lowContrastEdgeRatio, strongEdgeRatio, temporalFlash);
        }
    }

    private int[] previousGray;
    private long lastSaveMs = 0;
    private long frameIndex = 0;

    public Result analyze(Bitmap source, long nowMs) {
        frameIndex++;
        int targetW = Math.min(360, source.getWidth());
        int targetH = Math.max(1, Math.round(source.getHeight() * (targetW / (float) source.getWidth())));
        Bitmap small = source.getWidth() == targetW
                ? source.copy(Bitmap.Config.ARGB_8888, false)
                : Bitmap.createScaledBitmap(source, targetW, targetH, true);

        int w = small.getWidth();
        int h = small.getHeight();
        int n = w * h;
        int[] pixels = new int[n];
        int[] gray = new int[n];
        small.getPixels(pixels, 0, w, 0, 0, w, h);

        int min = 255;
        int max = 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            int g = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) >> 8;
            gray[i] = g;
            min = Math.min(min, g);
            max = Math.max(max, g);
            sum += g;
        }

        long low = 0;
        long strong = 0;
        long considered = 0;
        int[] tracePixels = new int[n];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                int gx = Math.abs(gray[i + 1] - gray[i - 1]);
                int gy = Math.abs(gray[i + w] - gray[i - w]);
                int grad = gx + gy;
                considered++;
                if (grad >= 5 && grad <= 28) {
                    low++;
                    tracePixels[i] = 0xffffffff;
                } else if (grad > 28) {
                    strong++;
                    tracePixels[i] = 0xff606060;
                } else {
                    tracePixels[i] = 0xff000000;
                }
            }
        }

        double flash = 0.0;
        if (previousGray != null && previousGray.length == gray.length) {
            long d = 0;
            for (int i = 0; i < n; i += 2) d += Math.abs(gray[i] - previousGray[i]);
            flash = d / (double) Math.max(1, n / 2);
        }
        previousGray = gray;

        double lowRatio = low / (double) Math.max(1, considered);
        double strongRatio = strong / (double) Math.max(1, considered);
        double faintDominance = low / (double) Math.max(1, strong);

        boolean flashCandidate = flash >= 17.0;
        boolean faintCandidate = lowRatio >= 0.055 && faintDominance >= 0.55 && strongRatio <= 0.28;
        boolean baseline = frameIndex % 60 == 0;
        boolean cooled = nowMs - lastSaveMs >= 1200;

        Result r = new Result();
        r.lowContrastEdgeRatio = lowRatio;
        r.strongEdgeRatio = strongRatio;
        r.temporalFlash = flash;
        r.save = cooled && (flashCandidate || faintCandidate || baseline);
        if (flashCandidate) r.kind = "single_frame_or_temporal_change";
        else if (faintCandidate) r.kind = "low_contrast_hidden_structure_candidate";
        else r.kind = "baseline_reference";

        if (r.save) {
            lastSaveMs = nowMs;
            Bitmap trace = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            trace.setPixels(tracePixels, 0, w, 0, 0, w, h);
            r.trace = trace;

            int span = Math.max(16, max - min);
            int[] revealPixels = new int[n];
            for (int i = 0; i < n; i++) {
                int v = Math.max(0, Math.min(255, (gray[i] - min) * 255 / span));
                revealPixels[i] = Color.rgb(v, v, v);
            }
            Bitmap reveal = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            reveal.setPixels(revealPixels, 0, w, 0, 0, w, h);
            r.reveal = reveal;
        }
        small.recycle();
        return r;
    }
}
