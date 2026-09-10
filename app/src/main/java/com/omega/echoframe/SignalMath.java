package com.omega.echoframe;

public final class SignalMath {
    private SignalMath() {}

    public static double rms(short[] x, int n) {
        if (n <= 0) return 0.0;
        double s = 0.0;
        for (int i = 0; i < n; i++) { double v = x[i] / 32768.0; s += v * v; }
        return Math.sqrt(s / n);
    }

    public static void normalize(float[] x) {
        float m = 1e-9f;
        for (float v : x) if (Math.abs(v) > m) m = Math.abs(v);
        for (int i = 0; i < x.length; i++) x[i] /= m;
    }

    public static float[] subtract(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a[i] - b[i];
        return out;
    }
}
