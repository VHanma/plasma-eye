package com.plasmacam;

public class PlasmaEngine {
    public static final int MODE_LIVE = 0;
    public static final int MODE_STACK = 1;
    public static final int MODE_DELTA = 2;
    public static final int MODE_RED_IR = 3;

    private static int[] previous;
    private static float[] stack;
    private static int stackCount = 0;

    public static int[] apply(int[] px, int w, int h, int mode) {
        switch (mode) {
            case MODE_STACK:
                return weakLightStack(px, w, h);

            case MODE_DELTA:
                return frameDelta(px, w, h);

            case MODE_RED_IR:
                return redInfraBias(px, w, h);

            case MODE_LIVE:
            default:
                return px;
        }
    }

    private static int[] weakLightStack(int[] px, int w, int h) {
        int n = w * h;

        if (stack == null || stack.length != n) {
            stack = new float[n * 3];
            stackCount = 0;
        }

        stackCount = Math.min(stackCount + 1, 24);

        float keep = (stackCount - 1f) / stackCount;
        float add = 1f / stackCount;

        for (int i = 0; i < n; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8) & 0xFF;
            int b = px[i] & 0xFF;

            stack[i * 3] = stack[i * 3] * keep + r * add;
            stack[i * 3 + 1] = stack[i * 3 + 1] * keep + g * add;
            stack[i * 3 + 2] = stack[i * 3 + 2] * keep + b * add;
        }

        int[] out = new int[n];

        int min = 255;
        int max = 0;

        int[] lum = new int[n];

        for (int i = 0; i < n; i++) {
            int r = clamp((int) stack[i * 3]);
            int g = clamp((int) stack[i * 3 + 1]);
            int b = clamp((int) stack[i * 3 + 2]);

            int y = (r * 77 + g * 150 + b * 29) >> 8;
            lum[i] = y;

            if (y < min) min = y;
            if (y > max) max = y;
        }

        int range = Math.max(4, max - min);

        for (int i = 0; i < n; i++) {
            int r = clamp((int) stack[i * 3]);
            int g = clamp((int) stack[i * 3 + 1]);
            int b = clamp((int) stack[i * 3 + 2]);

            int y = (lum[i] - min) * 255 / range;
            float gain = 0.35f + y / 255f;

            r = clamp((int)(r * gain * 2.2f));
            g = clamp((int)(g * gain * 2.2f));
            b = clamp((int)(b * gain * 2.2f));

            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        return out;
    }

    private static int[] frameDelta(int[] px, int w, int h) {
        int n = w * h;

        if (previous == null || previous.length != n) {
            previous = px.clone();
            return px;
        }

        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int r1 = (px[i] >> 16) & 0xFF;
            int g1 = (px[i] >> 8) & 0xFF;
            int b1 = px[i] & 0xFF;

            int r0 = (previous[i] >> 16) & 0xFF;
            int g0 = (previous[i] >> 8) & 0xFF;
            int b0 = previous[i] & 0xFF;

            int d = Math.abs(r1 - r0) + Math.abs(g1 - g0) + Math.abs(b1 - b0);
            d = clamp(d * 3);

            int base = ((r1 + g1 + b1) / 3) / 5;

            out[i] = 0xFF000000
                    | (clamp(base + d) << 16)
                    | (clamp(base + d / 2) << 8)
                    | base;
        }

        previous = px.clone();
        return out;
    }

    private static int[] redInfraBias(int[] px, int w, int h) {
        int n = w * h;
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8) & 0xFF;
            int b = px[i] & 0xFF;

            int redBias = r - ((g + b) / 2);
            int signal = clamp(Math.max(0, redBias) * 4);

            int y = (r * 77 + g * 150 + b * 29) >> 8;
            int base = y / 4;

            out[i] = 0xFF000000
                    | (clamp(base + signal) << 16)
                    | (clamp(base + signal / 4) << 8)
                    | base;
        }

        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
