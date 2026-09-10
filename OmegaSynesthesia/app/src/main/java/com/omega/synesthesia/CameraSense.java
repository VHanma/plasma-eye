package com.omega.synesthesia;

public final class CameraSense {
    public static final class Reading {
        public final float motion;
        public final float lightChange;
        public final float leftMotion;
        public final float rightMotion;
        Reading(float motion, float lightChange, float leftMotion, float rightMotion) {
            this.motion = motion;
            this.lightChange = lightChange;
            this.leftMotion = leftMotion;
            this.rightMotion = rightMotion;
        }
    }

    private byte[] previous;
    private float previousMean = -1f;

    public synchronized Reading process(byte[] y, int width, int height) {
        if (y == null || y.length < width * height) return new Reading(0,0,0,0);
        int sx = Math.max(2, width / 64);
        int sy = Math.max(2, height / 48);
        long sum = 0; int n = 0;
        double diff = 0, left = 0, right = 0;
        int nl = 0, nr = 0;
        if (previous == null || previous.length != width * height) previous = new byte[width * height];

        for (int yy = 0; yy < height; yy += sy) {
            for (int xx = 0; xx < width; xx += sx) {
                int i = yy * width + xx;
                int v = y[i] & 0xff;
                sum += v; n++;
                int d = Math.abs(v - (previous[i] & 0xff));
                diff += d;
                if (xx < width / 2) { left += d; nl++; } else { right += d; nr++; }
            }
        }
        System.arraycopy(y, 0, previous, 0, width * height);
        float mean = n == 0 ? 0f : (float)sum / n;
        float light = previousMean < 0 ? 0f : Math.abs(mean - previousMean) / 255f;
        previousMean = mean;
        float m = n == 0 ? 0f : (float)(diff / n / 255.0);
        float lm = nl == 0 ? 0f : (float)(left / nl / 255.0);
        float rm = nr == 0 ? 0f : (float)(right / nr / 255.0);
        return new Reading(Math.min(1f, m * 6f), Math.min(1f, light * 8f), Math.min(1f,lm*6f), Math.min(1f,rm*6f));
    }
}
