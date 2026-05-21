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

    private static final int MIN_LOCK_SCORE = 118;
    private static final int MIN_BLOB_CELLS = 8;
    private static final int MAX_LOCKS = 4;

    public static int[] apply(int[] px, int w, int h, int mode) {
        if (px == null || px.length != w * h) return px;

        switch (mode) {
            case MODE_LIVE:
                return blueForensicVision(px, w, h);

            case MODE_STACK:
                return bioSpiritLockVision(px, w, h);

            case MODE_DELTA:
                return motionOnlyBioLock(px, w, h);

            case MODE_RED_IR:
                return redInfraBioVision(px, w, h);

            default:
                return bioSpiritLockVision(px, w, h);
        }
    }

    private static int[] bioSpiritLockVision(int[] px, int w, int h) {
        int n = w * h;

        int[] gray = toGray(px, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);
        int[] motion = motionDiff(gray, w, h);
        int[] local = localContrast(gray, blur, w, h);

        ensureFloatArrays(n);
        frameCount++;

        for (int i = 0; i < n; i++) {
            if (frameCount < 5) {
                background[i] = gray[i];
            } else {
                background[i] = background[i] * 0.996f + gray[i] * 0.004f;
            }
        }

        int min = 255;
        int max = 0;

        for (int i = 0; i < n; i += 4) {
            int v = gray[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }

        int range = Math.max(10, max - min);
        int[] out = new int[n];
        int[] entitySignal = new int[n];

        for (int i = 0; i < n; i++) {
            int base = clamp((gray[i] - min) * 255 / range);
            int bgDiff = clamp((int)Math.abs(gray[i] - background[i]) * 3);

            int e = edge[i];
            int m = motion[i];
            int lc = local[i];

            int biologicalSignal = clamp(
                    (int)(
                            m * 2.8f +
                            bgDiff * 1.15f +
                            lc * 0.65f +
                            Math.max(0, e - 38) * 0.75f
                    )
            );

            // Stop hard static furniture edges from acting like entities.
            if (m < 10 && bgDiff < 12) {
                biologicalSignal = clamp(biologicalSignal / 3);
            }

            entitySignal[i] = biologicalSignal;

            if (biologicalSignal > persistence[i]) {
                persistence[i] = persistence[i] * 0.72f + biologicalSignal * 0.28f;
            } else {
                persistence[i] *= 0.974f;
            }

            int mem = clamp((int)persistence[i]);

            int r = clamp((int)(base * 0.04f + mem * 0.10f));
            int g = clamp((int)(base * 0.22f + e * 0.38f + mem * 0.42f));
            int b = clamp((int)(base * 0.72f + e * 0.70f + mem * 0.95f));

            if (mem > 145) {
                r = clamp(r + 80);
                g = clamp(g + 135);
                b = 255;
            }

            out[i] = argb(r, g, b);
        }

        drawSceneEdgesSubtle(out, edge, w, h);
        drawBioSpiritLocks(out, entitySignal, edge, motion, gray, w, h);

        prevGray = gray;
        return out;
    }

    private static int[] motionOnlyBioLock(int[] px, int w, int h) {
        int n = w * h;

        int[] gray = toGray(px, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);
        int[] motion = motionDiff(gray, w, h);

        ensureFloatArrays(n);

        int[] out = new int[n];
        int[] signal = new int[n];

        for (int i = 0; i < n; i++) {
            int sig = clamp(motion[i] * 5 + Math.max(0, edge[i] - 55));
            signal[i] = sig;

            if (sig > persistence[i]) persistence[i] = persistence[i] * 0.6f + sig * 0.4f;
            else persistence[i] *= 0.94f;

            int p = clamp((int)persistence[i]);
            int base = gray[i] / 10;

            int r = clamp(base + p);
            int g = clamp(base + p / 2);
            int b = clamp(base + edge[i] / 3);

            if (p > 150) {
                r = 255;
                g = clamp(80 + p / 2);
                b = clamp(60 + p / 3);
            }

            out[i] = argb(r, g, b);
        }

        drawBioSpiritLocks(out, signal, edge, motion, gray, w, h);

        prevGray = gray;
        return out;
    }

    private static int[] blueForensicVision(int[] px, int w, int h) {
        int n = w * h;
        int[] gray = toGray(px, w, h);
        int[] edge = sobel(boxBlur(gray, w, h, 1), w, h);

        int min = 255;
        int max = 0;

        for (int i = 0; i < n; i += 4) {
            int v = gray[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }

        int range = Math.max(10, max - min);
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int v = clamp((gray[i] - min) * 255 / range);
            int boosted = clamp((int)(Math.sqrt(v / 255.0) * 255.0));

            int r = boosted / 14;
            int g = boosted / 3 + edge[i] / 5;
            int b = clamp(boosted + 25 + edge[i] / 2);

            out[i] = argb(r, g, b);
        }

        return out;
    }

    private static int[] redInfraBioVision(int[] px, int w, int h) {
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

            out[i] = argb(
                    clamp(base + signal * 2),
                    clamp(base + signal / 2),
                    base / 2
            );
        }

        return out;
    }

    private static void drawBioSpiritLocks(
            int[] out,
            int[] signal,
            int[] edge,
            int[] motion,
            int[] gray,
            int w,
            int h
    ) {
        int gridW = 64;
        int gridH = Math.max(36, gridW * h / Math.max(1, w));

        int cells = gridW * gridH;
        int[] sums = new int[cells];
        int[] edgeSums = new int[cells];
        int[] motionSums = new int[cells];
        int[] graySums = new int[cells];
        int[] counts = new int[cells];

        for (int y = 0; y < h; y += 2) {
            int gy = y * gridH / h;

            for (int x = 0; x < w; x += 2) {
                int gx = x * gridW / w;
                int idx = gy * gridW + gx;
                int p = y * w + x;

                sums[idx] += signal[p];
                edgeSums[idx] += edge[p];
                motionSums[idx] += motion[p];
                graySums[idx] += gray[p];
                counts[idx]++;
            }
        }

        boolean[] hot = new boolean[cells];

        for (int i = 0; i < cells; i++) {
            int count = Math.max(1, counts[i]);
            int avgSignal = sums[i] / count;
            int avgMotion = motionSums[i] / count;
            int avgEdge = edgeSums[i] / count;

            // Low default sensitivity: do not even consider weak static edges.
            hot[i] = avgSignal > 70 && (avgMotion > 10 || avgSignal > 120) && avgEdge > 18;
        }

        boolean[] seen = new boolean[cells];
        int[] stack = new int[cells];
        int locks = 0;

        for (int i = 0; i < cells && locks < MAX_LOCKS; i++) {
            if (!hot[i] || seen[i]) continue;

            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;

            int minX = gridW;
            int minY = gridH;
            int maxX = 0;
            int maxY = 0;
            int totalCells = 0;
            int totalSignal = 0;
            int totalMotion = 0;
            int totalEdge = 0;

            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % gridW;
                int cy = cur / gridW;

                if (cx < minX) minX = cx;
                if (cy < minY) minY = cy;
                if (cx > maxX) maxX = cx;
                if (cy > maxY) maxY = cy;

                int count = Math.max(1, counts[cur]);

                totalSignal += sums[cur] / count;
                totalMotion += motionSums[cur] / count;
                totalEdge += edgeSums[cur] / count;
                totalCells++;

                int[][] neighbors = {
                        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
                };

                for (int[] nb : neighbors) {
                    int nx = cx + nb[0];
                    int ny = cy + nb[1];

                    if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;

                    int ni = ny * gridW + nx;

                    if (hot[ni] && !seen[ni]) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }
            }

            if (totalCells < MIN_BLOB_CELLS) continue;

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;

            if (bw <= 0 || bh <= 0) continue;

            float aspect = bh / (float)bw;
            float fill = totalCells / (float)(bw * bh);

            int avgSignal = totalSignal / Math.max(1, totalCells);
            int avgMotion = totalMotion / Math.max(1, totalCells);
            int avgEdge = totalEdge / Math.max(1, totalCells);

            int score = figureScore(
                    hot,
                    gridW,
                    gridH,
                    minX,
                    minY,
                    maxX,
                    maxY,
                    aspect,
                    fill,
                    avgSignal,
                    avgMotion,
                    avgEdge
            );

            if (score < MIN_LOCK_SCORE) continue;

            int x0 = clamp(minX * w / gridW - 8, 0, w - 1);
            int y0 = clamp(minY * h / gridH - 8, 0, h - 1);
            int x1 = clamp((maxX + 1) * w / gridW + 8, 0, w - 1);
            int y1 = clamp((maxY + 1) * h / gridH + 8, 0, h - 1);

            if ((x1 - x0) < w * 0.045f || (y1 - y0) < h * 0.055f) continue;
            if ((x1 - x0) > w * 0.80f || (y1 - y0) > h * 0.90f) continue;

            int lockColor;

            if (aspect > 1.25f) {
                lockColor = argb(255, 90, 40);      // human/spirit vertical lock
                drawHumanSpiritSilhouette(out, w, h, x0, y0, x1, y1, lockColor, score);
            } else {
                lockColor = argb(255, 180, 40);     // animal/low compact lock
                drawAnimalSilhouette(out, w, h, x0, y0, x1, y1, lockColor, score);
            }

            locks++;
        }
    }

    private static int figureScore(
            boolean[] hot,
            int gridW,
            int gridH,
            int minX,
            int minY,
            int maxX,
            int maxY,
            float aspect,
            float fill,
            int avgSignal,
            int avgMotion,
            int avgEdge
    ) {
        int score = 0;

        // Human/spirit standing shape.
        if (aspect >= 1.15f && aspect <= 4.8f) score += 38;

        // Animal/creature compact shape.
        if (aspect >= 0.35f && aspect < 1.25f) score += 24;

        // Reject huge solid rectangles/furniture.
        if (fill >= 0.10f && fill <= 0.72f) score += 22;
        else score -= 28;

        if (avgSignal > 95) score += 22;
        if (avgMotion > 14) score += 26;
        if (avgEdge > 28) score += 14;

        int symmetry = symmetryScore(hot, gridW, gridH, minX, minY, maxX, maxY);
        score += symmetry;

        int organic = organicScore(hot, gridW, gridH, minX, minY, maxX, maxY);
        score += organic;

        // Penalize perfect rectangles and wall-like blocks.
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;

        if (bw > gridW * 0.45f && bh < gridH * 0.18f) score -= 45;
        if (bh > gridH * 0.55f && bw > gridW * 0.42f) score -= 35;

        return score;
    }

    private static int symmetryScore(boolean[] hot, int gridW, int gridH, int minX, int minY, int maxX, int maxY) {
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        if (bw <= 2 || bh <= 2) return 0;

        int hits = 0;
        int pairs = 0;

        for (int y = minY; y <= maxY; y++) {
            for (int dx = 0; dx < bw / 2; dx++) {
                int lx = minX + dx;
                int rx = maxX - dx;

                boolean a = hot[y * gridW + lx];
                boolean b = hot[y * gridW + rx];

                if (a || b) {
                    pairs++;
                    if (a && b) hits++;
                }
            }
        }

        if (pairs == 0) return 0;

        float s = hits / (float)pairs;

        if (s > 0.62f) return 26;
        if (s > 0.42f) return 15;
        return 0;
    }

    private static int organicScore(boolean[] hot, int gridW, int gridH, int minX, int minY, int maxX, int maxY) {
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        if (bw <= 2 || bh <= 2) return 0;

        int rowChanges = 0;
        int activeRows = 0;
        int prevWidth = -1;

        for (int y = minY; y <= maxY; y++) {
            int rowMin = 9999;
            int rowMax = -1;

            for (int x = minX; x <= maxX; x++) {
                if (hot[y * gridW + x]) {
                    if (x < rowMin) rowMin = x;
                    if (x > rowMax) rowMax = x;
                }
            }

            if (rowMax >= rowMin) {
                int width = rowMax - rowMin + 1;

                if (prevWidth >= 0 && Math.abs(width - prevWidth) > 1) {
                    rowChanges++;
                }

                prevWidth = width;
                activeRows++;
            }
        }

        if (activeRows < 3) return 0;

        float variation = rowChanges / (float)activeRows;

        // Organic bodies vary row by row; furniture often stays constant.
        if (variation > 0.32f && variation < 0.88f) return 25;
        if (variation > 0.18f) return 12;

        return -18;
    }

    private static void drawHumanSpiritSilhouette(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color, int score) {
        int cx = (x0 + x1) / 2;
        int height = y1 - y0;
        int width = x1 - x0;

        int headR = clamp(height / 10, 5, 24);
        int headY = y0 + height / 7;
        int neckY = y0 + height / 4;
        int chestY = y0 + height / 2;
        int hipY = y0 + height * 68 / 100;

        int alphaColor = color;

        drawCircle(out, w, h, cx, headY, headR, alphaColor, 3);

        drawLine(out, w, h, cx, headY + headR, cx, hipY, alphaColor, 3);

        drawLine(out, w, h, cx - width / 3, neckY + height / 12, cx + width / 3, neckY + height / 12, alphaColor, 3);

        drawLine(out, w, h, cx - width / 3, neckY + height / 12, cx - width / 2, chestY, alphaColor, 2);
        drawLine(out, w, h, cx + width / 3, neckY + height / 12, cx + width / 2, chestY, alphaColor, 2);

        drawLine(out, w, h, cx, hipY, cx - width / 3, y1 - 4, alphaColor, 3);
        drawLine(out, w, h, cx, hipY, cx + width / 3, y1 - 4, alphaColor, 3);

        drawRectCorners(out, w, h, x0, y0, x1, y1, argb(255, 255, 255), score > 150 ? 5 : 3);
    }

    private static void drawAnimalSilhouette(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color, int score) {
        int cx0 = x0 + (x1 - x0) / 5;
        int cx1 = x1 - (x1 - x0) / 5;
        int cy = (y0 + y1) / 2;
        int height = y1 - y0;

        drawLine(out, w, h, cx0, cy, cx1, cy, color, 4);
        drawLine(out, w, h, cx1, cy, x1 - 3, cy - height / 5, color, 3);

        drawLine(out, w, h, cx0, cy, x0 + 4, y1 - 3, color, 3);
        drawLine(out, w, h, cx1, cy, x1 - 4, y1 - 3, color, 3);

        drawCircle(out, w, h, x1 - (x1 - x0) / 8, cy - height / 5, Math.max(4, height / 8), color, 2);
        drawRectCorners(out, w, h, x0, y0, x1, y1, argb(255, 255, 255), score > 150 ? 5 : 3);
    }

    private static void drawSceneEdgesSubtle(int[] out, int[] edge, int w, int h) {
        int n = w * h;

        for (int i = 0; i < n; i++) {
            if (edge[i] > 165) {
                int r = (out[i] >> 16) & 0xFF;
                int g = (out[i] >> 8) & 0xFF;
                int b = out[i] & 0xFF;

                out[i] = argb(clamp(r + 18), clamp(g + 38), clamp(b + 54));
            }
        }
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
            out[i] = clamp((d - 4) * 6);
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

    private static void drawRectCorners(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color, int thick) {
        int lenX = Math.max(12, (x1 - x0) / 4);
        int lenY = Math.max(12, (y1 - y0) / 4);

        drawLine(out, w, h, x0, y0, x0 + lenX, y0, color, thick);
        drawLine(out, w, h, x0, y0, x0, y0 + lenY, color, thick);

        drawLine(out, w, h, x1, y0, x1 - lenX, y0, color, thick);
        drawLine(out, w, h, x1, y0, x1, y0 + lenY, color, thick);

        drawLine(out, w, h, x0, y1, x0 + lenX, y1, color, thick);
        drawLine(out, w, h, x0, y1, x0, y1 - lenY, color, thick);

        drawLine(out, w, h, x1, y1, x1 - lenX, y1, color, thick);
        drawLine(out, w, h, x1, y1, x1, y1 - lenY, color, thick);
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

        out[idx] = argb(
                (sr * alpha + dr * inv) / 255,
                (sg * alpha + dg * inv) / 255,
                (sb * alpha + db * inv) / 255
        );
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
