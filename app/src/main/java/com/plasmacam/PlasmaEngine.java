package com.plasmacam;

public class PlasmaEngine {
    public static final int MODE_LIVE = 0;
    public static final int MODE_STACK = 1;
    public static final int MODE_DELTA = 2;
    public static final int MODE_RED_IR = 3;

    private static int[] prevGray;
    private static float[] persistence;
    private static float[] background;
    private static int frameCount = 0;

    public static int[] apply(int[] px, int w, int h, int mode) {
        if (px == null || px.length != w * h) return px;

        switch (mode) {
            case MODE_LIVE:
                return blueNightVision(px, w, h);

            case MODE_STACK:
                return specterXrayVision(px, w, h);

            case MODE_DELTA:
                return targetMotionVision(px, w, h);

            case MODE_RED_IR:
                return redInfraVision(px, w, h);

            default:
                return specterXrayVision(px, w, h);
        }
    }

    private static int[] specterXrayVision(int[] px, int w, int h) {
        int n = w * h;
        int[] gray = toGray(px, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);
        int[] motion = motionDiff(gray, w, h);
        int[] local = localContrast(gray, blur, w, h);

        ensureFloatArrays(n);

        frameCount++;

        for (int i = 0; i < n; i++) {
            if (frameCount < 4) {
                background[i] = gray[i];
            } else {
                background[i] = background[i] * 0.992f + gray[i] * 0.008f;
            }
        }

        int min = 255;
        int max = 0;

        for (int i = 0; i < n; i += 4) {
            int v = gray[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }

        int range = Math.max(8, max - min);

        int[] signal = new int[n];
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int base = clamp((gray[i] - min) * 255 / range);

            int bgDiff = clamp((int)Math.abs(gray[i] - background[i]) * 3);
            int e = edge[i];
            int m = motion[i];
            int lc = local[i];

            int sig = clamp((int)(e * 1.35f + m * 2.6f + lc * 0.95f + bgDiff * 0.9f));
            signal[i] = sig;

            if (sig > persistence[i]) {
                persistence[i] = persistence[i] * 0.55f + sig * 0.45f;
            } else {
                persistence[i] *= 0.955f;
            }

            int mem = clamp((int)persistence[i]);

            int r = clamp((int)(base * 0.07f + m * 0.42f + mem * 0.10f));
            int g = clamp((int)(base * 0.20f + e * 0.78f + mem * 0.48f));
            int b = clamp((int)(base * 0.58f + e * 1.05f + mem * 1.22f));

            if (mem > 130 || sig > 180) {
                r = clamp(r + 80);
                g = clamp(g + 120);
                b = 255;
            }

            if (e > 105 && mem > 80) {
                r = 235;
                g = 255;
                b = 255;
            }

            out[i] = argb(r, g, b);
        }

        drawHardSceneEdges(out, edge, w, h);
        drawTargetBoxes(out, signal, w, h, true);

        prevGray = gray;
        return out;
    }

    private static int[] targetMotionVision(int[] px, int w, int h) {
        int n = w * h;
        int[] gray = toGray(px, w, h);
        int[] motion = motionDiff(gray, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);

        ensureFloatArrays(n);

        int[] out = new int[n];
        int[] signal = new int[n];

        for (int i = 0; i < n; i++) {
            int sig = clamp(motion[i] * 4 + edge[i]);
            signal[i] = sig;

            if (sig > persistence[i]) persistence[i] = sig;
            else persistence[i] *= 0.93f;

            int p = clamp((int)persistence[i]);
            int base = gray[i] / 8;

            int r = clamp(base + p * 2);
            int g = clamp(base + p);
            int b = clamp(base + edge[i] / 2);

            if (p > 90) {
                r = 255;
                g = clamp(80 + p);
                b = clamp(40 + p / 2);
            }

            out[i] = argb(r, g, b);
        }

        drawTargetBoxes(out, signal, w, h, true);

        prevGray = gray;
        return out;
    }

    private static int[] blueNightVision(int[] px, int w, int h) {
        int n = w * h;
        int[] gray = toGray(px, w, h);

        int min = 255;
        int max = 0;

        for (int i = 0; i < n; i += 4) {
            int v = gray[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }

        int range = Math.max(8, max - min);
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int v = clamp((gray[i] - min) * 255 / range);
            int boosted = clamp((int)(Math.sqrt(v / 255.0) * 255.0));

            int r = boosted / 12;
            int g = boosted / 3;
            int b = clamp(boosted + 35);

            out[i] = argb(r, g, b);
        }

        return out;
    }

    private static int[] redInfraVision(int[] px, int w, int h) {
        int n = w * h;
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8) & 0xFF;
            int b = px[i] & 0xFF;

            int redBias = r - ((g + b) / 2);
            int signal = clamp(Math.max(0, redBias) * 5);

            int y = (r * 77 + g * 150 + b * 29) >> 8;
            int base = y / 5;

            int rr = clamp(base + signal * 2);
            int gg = clamp(base + signal / 2);
            int bb = clamp(base / 2);

            out[i] = argb(rr, gg, bb);
        }

        return out;
    }

    private static int[] toGray(int[] px, int w, int h) {
        int n = w * h;
        int[] g = new int[n];

        for (int i = 0; i < n; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int gv = (px[i] >> 8) & 0xFF;
            int b = px[i] & 0xFF;

            g[i] = (r * 77 + gv * 150 + b * 29) >> 8;
        }

        return g;
    }

    private static int[] motionDiff(int[] gray, int w, int h) {
        int n = w * h;
        int[] out = new int[n];

        if (prevGray == null || prevGray.length != n) {
            prevGray = gray.clone();
            return out;
        }

        for (int i = 0; i < n; i++) {
            int d = Math.abs(gray[i] - prevGray[i]);
            out[i] = clamp((d - 3) * 6);
        }

        return out;
    }

    private static int[] localContrast(int[] gray, int[] blur, int w, int h) {
        int n = w * h;
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            out[i] = clamp(Math.abs(gray[i] - blur[i]) * 3);
        }

        return out;
    }

    private static int[] sobel(int[] g, int w, int h) {
        int[] out = new int[w * h];

        for (int y = 1; y < h - 1; y++) {
            int yw = y * w;

            for (int x = 1; x < w - 1; x++) {
                int i = yw + x;

                int gx =
                        -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1]
                        + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1];

                int gy =
                        -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1]
                        + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1];

                out[i] = clamp((int)Math.sqrt(gx * gx + gy * gy) / 4);
            }
        }

        return out;
    }

    private static int[] boxBlur(int[] src, int w, int h, int radius) {
        int n = w * h;
        int[] tmp = new int[n];
        int[] out = new int[n];

        for (int y = 0; y < h; y++) {
            int row = y * w;

            for (int x = 0; x < w; x++) {
                int sum = 0;
                int count = 0;

                for (int dx = -radius; dx <= radius; dx++) {
                    int xx = x + dx;

                    if (xx >= 0 && xx < w) {
                        sum += src[row + xx];
                        count++;
                    }
                }

                tmp[row + x] = sum / count;
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                int count = 0;

                for (int dy = -radius; dy <= radius; dy++) {
                    int yy = y + dy;

                    if (yy >= 0 && yy < h) {
                        sum += tmp[yy * w + x];
                        count++;
                    }
                }

                out[y * w + x] = sum / count;
            }
        }

        return out;
    }

    private static void drawHardSceneEdges(int[] out, int[] edge, int w, int h) {
        int n = w * h;

        for (int i = 0; i < n; i++) {
            if (edge[i] > 145) {
                out[i] = argb(220, 255, 255);
            } else if (edge[i] > 100) {
                int r = (out[i] >> 16) & 0xFF;
                int g = (out[i] >> 8) & 0xFF;
                int b = out[i] & 0xFF;

                out[i] = argb(
                        clamp(r + 30),
                        clamp(g + 80),
                        clamp(b + 100)
                );
            }
        }
    }

    private static void drawTargetBoxes(int[] out, int[] signal, int w, int h, boolean skeleton) {
        int gridW = 56;
        int gridH = Math.max(28, gridW * h / Math.max(1, w));

        int cells = gridW * gridH;
        int[] sums = new int[cells];
        int[] counts = new int[cells];

        for (int y = 0; y < h; y += 2) {
            int gy = y * gridH / h;

            for (int x = 0; x < w; x += 2) {
                int gx = x * gridW / w;
                int idx = gy * gridW + gx;

                sums[idx] += signal[y * w + x];
                counts[idx]++;
            }
        }

        boolean[] hot = new boolean[cells];

        for (int i = 0; i < cells; i++) {
            int avg = counts[i] == 0 ? 0 : sums[i] / counts[i];
            hot[i] = avg > 72;
        }

        boolean[] seen = new boolean[cells];
        int[] stack = new int[cells];

        int drawn = 0;

        for (int i = 0; i < cells && drawn < 7; i++) {
            if (!hot[i] || seen[i]) continue;

            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;

            int minX = gridW;
            int minY = gridH;
            int maxX = 0;
            int maxY = 0;
            int total = 0;

            while (sp > 0) {
                int cur = stack[--sp];

                int cx = cur % gridW;
                int cy = cur / gridW;

                if (cx < minX) minX = cx;
                if (cy < minY) minY = cy;
                if (cx > maxX) maxX = cx;
                if (cy > maxY) maxY = cy;

                total++;

                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (Math.abs(ox) + Math.abs(oy) != 1) continue;

                        int nx = cx + ox;
                        int ny = cy + oy;

                        if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;

                        int ni = ny * gridW + nx;

                        if (hot[ni] && !seen[ni]) {
                            seen[ni] = true;
                            stack[sp++] = ni;
                        }
                    }
                }
            }

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;

            if (total < 5 || bw < 2 || bh < 2) continue;

            int x0 = clamp(minX * w / gridW - 8, 0, w - 1);
            int y0 = clamp(minY * h / gridH - 8, 0, h - 1);
            int x1 = clamp((maxX + 1) * w / gridW + 8, 0, w - 1);
            int y1 = clamp((maxY + 1) * h / gridH + 8, 0, h - 1);

            int color = argb(255, 80, 45);

            drawRect(out, w, h, x0, y0, x1, y1, color, 3);

            if (skeleton) {
                int cx = (x0 + x1) / 2;
                int top = y0 + (y1 - y0) / 5;
                int mid = y0 + (y1 - y0) / 2;
                int bot = y1 - (y1 - y0) / 8;

                int sk = argb(255, 235, 185);

                drawLine(out, w, h, cx, top, cx, bot, sk, 2);
                drawLine(out, w, h, x0 + 6, mid, x1 - 6, mid, sk, 2);
                drawLine(out, w, h, cx, bot, x0 + 8, y1 - 4, sk, 2);
                drawLine(out, w, h, cx, bot, x1 - 8, y1 - 4, sk, 2);

                int headR = Math.max(5, Math.min(18, (y1 - y0) / 8));
                drawCircle(out, w, h, cx, top, headR, sk, 2);
            }

            drawn++;
        }
    }

    private static void drawRect(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color, int thick) {
        for (int t = 0; t < thick; t++) {
            drawLine(out, w, h, x0, y0 + t, x1, y0 + t, color, 1);
            drawLine(out, w, h, x0, y1 - t, x1, y1 - t, color, 1);
            drawLine(out, w, h, x0 + t, y0, x0 + t, y1, color, 1);
            drawLine(out, w, h, x1 - t, y0, x1 - t, y1, color, 1);
        }
    }

    private static void drawCircle(int[] out, int w, int h, int cx, int cy, int r, int color, int thick) {
        int r2 = r * r;
        int inner = Math.max(0, r - thick);
        int inner2 = inner * inner;

        for (int y = cy - r; y <= cy + r; y++) {
            if (y < 0 || y >= h) continue;

            for (int x = cx - r; x <= cx + r; x++) {
                if (x < 0 || x >= w) continue;

                int dx = x - cx;
                int dy = y - cy;
                int d2 = dx * dx + dy * dy;

                if (d2 <= r2 && d2 >= inner2) {
                    blendPixel(out, y * w + x, color, 230);
                }
            }
        }
    }

    private static void drawLine(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color, int thick) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int err = dx - dy;

        while (true) {
            plotThick(out, w, h, x0, y0, color, thick);

            if (x0 == x1 && y0 == y1) break;

            int e2 = 2 * err;

            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }

            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static void plotThick(int[] out, int w, int h, int x, int y, int color, int thick) {
        for (int yy = y - thick; yy <= y + thick; yy++) {
            if (yy < 0 || yy >= h) continue;

            for (int xx = x - thick; xx <= x + thick; xx++) {
                if (xx < 0 || xx >= w) continue;

                blendPixel(out, yy * w + xx, color, 220);
            }
        }
    }

    private static void blendPixel(int[] out, int idx, int color, int alpha) {
        if (idx < 0 || idx >= out.length) return;

        int dst = out[idx];

        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;

        int sr = (color >> 16) & 0xFF;
        int sg = (color >> 8) & 0xFF;
        int sb = color & 0xFF;

        int inv = 255 - alpha;

        int r = (sr * alpha + dr * inv) / 255;
        int g = (sg * alpha + dg * inv) / 255;
        int b = (sb * alpha + db * inv) / 255;

        out[idx] = argb(r, g, b);
    }

    private static void ensureFloatArrays(int n) {
        if (persistence == null || persistence.length != n) {
            persistence = new float[n];
        }

        if (background == null || background.length != n) {
            background = new float[n];
            frameCount = 0;
        }
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
