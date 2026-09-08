package com.vaanhanma.sonifyforge;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import java.io.InputStream;
import java.util.Locale;

/** Converts any Android-decodable still image into a numeric feature table for Sonify Forge. */
public final class ImageFeatureExtractor {
    private static final int MAX_DECODE_EDGE = 1400;
    private static final int TARGET_CELLS = 1200;
    private static final int MAX_GRID_EDGE = 64;

    public static final class Result {
        public final CsvData data;
        public final int imageWidth;
        public final int imageHeight;
        public final int gridWidth;
        public final int gridHeight;

        Result(CsvData data, int imageWidth, int imageHeight, int gridWidth, int gridHeight) {
            this.data = data;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
        }

        public String describe() {
            return String.format(Locale.US,
                    "%d×%d image  •  %d×%d feature grid  •  %d samples  •  %d channels",
                    imageWidth, imageHeight, gridWidth, gridHeight, data.rowCount, data.columnCount - 1);
        }
    }

    public static Result extract(ContentResolver resolver, Uri uri) throws Exception {
        if (resolver == null || uri == null) throw new IllegalArgumentException("Choose an image first");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Could not open image");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int originalW = bounds.outWidth;
        int originalH = bounds.outHeight;
        if (originalW <= 0 || originalH <= 0) throw new IllegalArgumentException("Android could not decode this image format");

        int sample = 1;
        while (Math.max(originalW / sample, originalH / sample) > MAX_DECODE_EDGE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Could not open image");
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) throw new IllegalArgumentException("Android could not decode this image");

        try {
            double aspect = originalW / (double) originalH;
            int gridW = (int) Math.round(Math.sqrt(TARGET_CELLS * aspect));
            gridW = clamp(gridW, 8, Math.min(MAX_GRID_EDGE, originalW));
            int gridH = (int) Math.round(TARGET_CELLS / (double) gridW);
            gridH = clamp(gridH, 8, Math.min(MAX_GRID_EDGE, originalH));

            Bitmap grid = Bitmap.createScaledBitmap(decoded, gridW, gridH, true);
            try {
                return fromGrid(grid, originalW, originalH);
            } finally {
                if (grid != decoded) grid.recycle();
            }
        } finally {
            if (!decoded.isRecycled()) decoded.recycle();
        }
    }

    private static Result fromGrid(Bitmap grid, int originalW, int originalH) {
        int w = grid.getWidth();
        int h = grid.getHeight();
        int[] pixels = new int[w * h];
        grid.getPixels(pixels, 0, w, 0, 0, w, h);

        double[] luma = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            luma[i] = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
        }

        String[] headers = {
                "time", "x", "y", "brightness", "red", "green", "blue",
                "hue", "saturation", "edge", "contrast", "warmth"
        };
        double[][] rows = new double[w * h][headers.length];
        int row = 0;
        float[] hsv = new float[3];

        // Serpentine traversal keeps consecutive samples spatially adjacent while still covering the full 2D image.
        for (int y = 0; y < h; y++) {
            boolean reverse = (y & 1) == 1;
            for (int step = 0; step < w; step++) {
                int x = reverse ? (w - 1 - step) : step;
                int idx = y * w + x;
                int c = pixels[idx];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                Color.RGBToHSV(r, g, b, hsv);

                double edge = edgeStrength(luma, w, h, x, y);
                double contrast = localContrast(luma, w, h, x, y);

                double[] out = rows[row];
                out[0] = row;
                out[1] = w <= 1 ? 0 : x / (double) (w - 1);
                out[2] = h <= 1 ? 0 : y / (double) (h - 1);
                out[3] = luma[idx];
                out[4] = r;
                out[5] = g;
                out[6] = b;
                out[7] = hsv[0];
                out[8] = hsv[1] * 100.0;
                out[9] = edge;
                out[10] = contrast;
                out[11] = r - b;
                row++;
            }
        }

        return new Result(CsvData.fromValues(headers, rows), originalW, originalH, w, h);
    }

    private static double edgeStrength(double[] luma, int w, int h, int x, int y) {
        double center = luma[y * w + x];
        double sum = 0;
        int n = 0;
        if (x > 0) { sum += Math.abs(center - luma[y * w + (x - 1)]); n++; }
        if (x + 1 < w) { sum += Math.abs(center - luma[y * w + (x + 1)]); n++; }
        if (y > 0) { sum += Math.abs(center - luma[(y - 1) * w + x]); n++; }
        if (y + 1 < h) { sum += Math.abs(center - luma[(y + 1) * w + x]); n++; }
        return n == 0 ? 0 : sum / n;
    }

    private static double localContrast(double[] luma, int w, int h, int x, int y) {
        double sum = 0, sumSq = 0;
        int n = 0;
        for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++) {
            for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
                double v = luma[yy * w + xx];
                sum += v;
                sumSq += v * v;
                n++;
            }
        }
        if (n <= 1) return 0;
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sumSq / n - mean * mean));
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return Math.max(1, max);
        return Math.max(min, Math.min(max, v));
    }

    private ImageFeatureExtractor() {}
}
