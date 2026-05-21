package com.plasmacam;

public class PlasmaEngine {
    public static final int MODE_LIVE = 0;
    public static final int MODE_STACK = 1;
    public static final int MODE_DELTA = 2;
    public static final int MODE_RED_IR = 3;

    private static int activeFilter = MODE_STACK;

    public static void nextFilter() {
        if (activeFilter == MODE_STACK) activeFilter = MODE_DELTA;
        else if (activeFilter == MODE_DELTA) activeFilter = MODE_RED_IR;
        else if (activeFilter == MODE_RED_IR) activeFilter = MODE_LIVE;
        else activeFilter = MODE_STACK;
    }

    public static String filterName() {
        if (activeFilter == MODE_STACK) return "WRAITH LOCK";
        if (activeFilter == MODE_DELTA) return "MOTION HUNTER";
        if (activeFilter == MODE_RED_IR) return "RED BIO TRACE";
        if (activeFilter == MODE_LIVE) return "TACTICAL BLUE";
        return "WRAITH LOCK";
    }

    private static int[] prevGray;
    private static float[] background;
    private static float[] entityMemory;
    private static float[] wallTraceMemory;
    private static int frameCount = 0;

    private static final int MAX_LOCKS = 3;

    public static int[] apply(int[] px, int w, int h, int mode) {
        if (px == null || px.length != w * h) return px;

        int selected = activeFilter;

        switch (selected) {
            case MODE_LIVE:
                return darkForensicView(px, w, h);

            case MODE_STACK:
                return wallTraceEntityView(px, w, h);

            case MODE_DELTA:
                return pureEntityMotionView(px, w, h);

            case MODE_RED_IR:
                return redSpecterView(px, w, h);

            default:
                return wallTraceEntityView(px, w, h);
        }
    }

    private static int[] wallTraceEntityView(int[] px, int w, int h) {
        int n = w * h;

        int[] gray = toGray(px, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);
        int[] motion = motionDiff(gray, w, h);
        int[] local = localContrast(gray, blur, w, h);

        ensureMemory(n);
        frameCount++;

        for (int i = 0; i < n; i++) {
            if (frameCount < 8) {
                background[i] = gray[i];
            } else {
                background[i] = background[i] * 0.9975f + gray[i] * 0.0025f;
            }
        }

        int min = 255;
        int max = 0;

        for (int i = 0; i < n; i += 4) {
            int v = gray[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }

        int range = Math.max(12, max - min);
        int[] out = new int[n];
        int[] candidate = new int[n];

        for (int i = 0; i < n; i++) {
            int base = clamp((gray[i] - min) * 255 / range);
            int bgDiff = clamp((int)Math.abs(gray[i] - background[i]) * 4);

            int staticEdge = edge[i];
            int moving = motion[i];
            int texture = local[i];

            int organic = clamp(
                    moving * 4 +
                    bgDiff * 2 +
                    Math.max(0, texture - 10) +
                    Math.max(0, staticEdge - 48)
            );

            // Critical: suppress dead furniture / door / wall edges.
            if (moving < 8 && bgDiff < 10) organic = organic / 5;
            if (staticEdge > 165 && moving < 14) organic = organic / 4;

            candidate[i] = organic;

            if (organic > entityMemory[i]) {
                entityMemory[i] = entityMemory[i] * 0.70f + organic * 0.30f;
            } else {
                entityMemory[i] *= 0.982f;
            }

            // WallTrace memory: very slow accumulator. It only brightens when the return repeats.
            if (entityMemory[i] > 95) {
                wallTraceMemory[i] = Math.min(255f, wallTraceMemory[i] * 0.985f + entityMemory[i] * 0.045f);
            } else {
                wallTraceMemory[i] *= 0.992f;
            }

            int mem = clamp((int)wallTraceMemory[i]);

            int r = clamp((int)(base * 0.035f + mem * 0.06f));
            int g = clamp((int)(base * 0.20f + staticEdge * 0.26f + mem * 0.34f));
            int b = clamp((int)(base * 0.74f + staticEdge * 0.42f + mem * 0.90f));

            if (mem > 125) {
                r = clamp(r + 42);
                g = clamp(g + 96);
                b = 255;
            }

            out[i] = argb(r, g, b);
        }

        drawSubtleWorldEdges(out, edge, w, h);
        drawConfirmedEntities(out, candidate, edge, motion, gray, w, h, true);

        prevGray = gray;
        return out;
    }

    private static int[] pureEntityMotionView(int[] px, int w, int h) {
        int n = w * h;

        int[] gray = toGray(px, w, h);
        int[] blur = boxBlur(gray, w, h, 2);
        int[] edge = sobel(blur, w, h);
        int[] motion = motionDiff(gray, w, h);

        ensureMemory(n);

        int[] out = new int[n];
        int[] candidate = new int[n];

        for (int i = 0; i < n; i++) {
            int sig = clamp(motion[i] * 6 + Math.max(0, edge[i] - 80));
            if (motion[i] < 6) sig = sig / 4;

            candidate[i] = sig;

            if (sig > entityMemory[i]) entityMemory[i] = entityMemory[i] * 0.65f + sig * 0.35f;
            else entityMemory[i] *= 0.94f;

            int mem = clamp((int)entityMemory[i]);
            int base = gray[i] / 11;

            int r = clamp(base + mem);
            int g = clamp(base + mem / 2);
            int b = clamp(base + edge[i] / 4);

            if (mem > 145) {
                r = 255;
                g = clamp(90 + mem / 2);
                b = clamp(50 + mem / 3);
            }

            out[i] = argb(r, g, b);
        }

        drawConfirmedEntities(out, candidate, edge, motion, gray, w, h, false);

        prevGray = gray;
        return out;
    }

    private static int[] darkForensicView(int[] px, int w, int h) {
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

        int range = Math.max(12, max - min);
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            int v = clamp((gray[i] - min) * 255 / range);
            int boosted = clamp((int)(Math.sqrt(v / 255.0) * 255.0));

            int r = boosted / 16;
            int g = boosted / 4 + edge[i] / 8;
            int b = clamp(boosted + edge[i] / 3);

            out[i] = argb(r, g, b);
        }

        return out;
    }

    private static int[] redSpecterView(int[] px, int w, int h) {
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

    private static void drawConfirmedEntities(
            int[] out,
            int[] signal,
            int[] edge,
            int[] motion,
            int[] gray,
            int w,
            int h,
            boolean wallTrace
    ) {
        int gridW = 72;
        int gridH = Math.max(40, gridW * h / Math.max(1, w));

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
            int c = Math.max(1, counts[i]);
            int avgSignal = sums[i] / c;
            int avgMotion = motionSums[i] / c;
            int avgEdge = edgeSums[i] / c;

            // Low sensitivity: must have organic activity or strong repeat return.
            hot[i] =
                    avgSignal > 92 &&
                    avgEdge > 16 &&
                    (avgMotion > 10 || avgSignal > 145);
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
            int totalGray = 0;

            while (sp > 0) {
                int cur = stack[--sp];

                int cx = cur % gridW;
                int cy = cur / gridW;

                if (cx < minX) minX = cx;
                if (cy < minY) minY = cy;
                if (cx > maxX) maxX = cx;
                if (cy > maxY) maxY = cy;

                int c = Math.max(1, counts[cur]);

                totalSignal += sums[cur] / c;
                totalMotion += motionSums[cur] / c;
                totalEdge += edgeSums[cur] / c;
                totalGray += graySums[cur] / c;
                totalCells++;

                int nx;
                int ny;
                int ni;

                nx = cx + 1; ny = cy;
                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                    ni = ny * gridW + nx;
                    if (hot[ni] && !seen[ni]) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }

                nx = cx - 1; ny = cy;
                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                    ni = ny * gridW + nx;
                    if (hot[ni] && !seen[ni]) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }

                nx = cx; ny = cy + 1;
                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                    ni = ny * gridW + nx;
                    if (hot[ni] && !seen[ni]) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }

                nx = cx; ny = cy - 1;
                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                    ni = ny * gridW + nx;
                    if (hot[ni] && !seen[ni]) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }
            }

            if (totalCells < 9) continue;

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            if (bw <= 0 || bh <= 0) continue;

            float aspect = bh / (float)bw;
            float fill = totalCells / (float)(bw * bh);

            int avgSignal = totalSignal / Math.max(1, totalCells);
            int avgMotion = totalMotion / Math.max(1, totalCells);
            int avgEdge = totalEdge / Math.max(1, totalCells);
            int avgGray = totalGray / Math.max(1, totalCells);

            int score = figureScore(hot, gridW, gridH, minX, minY, maxX, maxY, aspect, fill, avgSignal, avgMotion, avgEdge, avgGray);

            if (score < 132) continue;

            int x0 = clamp(minX * w / gridW - 10, 0, w - 1);
            int y0 = clamp(minY * h / gridH - 10, 0, h - 1);
            int x1 = clamp((maxX + 1) * w / gridW + 10, 0, w - 1);
            int y1 = clamp((maxY + 1) * h / gridH + 10, 0, h - 1);

            int pw = x1 - x0;
            int ph = y1 - y0;

            if (pw < w * 0.045f || ph < h * 0.055f) continue;
            if (pw > w * 0.78f || ph > h * 0.88f) continue;

            if (aspect >= 1.08f) {
                drawSpiritHumanLock(out, w, h, x0, y0, x1, y1, score, wallTrace);
            } else {
                drawAnimalLock(out, w, h, x0, y0, x1, y1, score, wallTrace);
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
            int avgEdge,
            int avgGray
    ) {
        int score = 0;

        if (aspect >= 1.08f && aspect <= 4.7f) score += 42;
        if (aspect >= 0.35f && aspect < 1.08f) score += 25;

        if (fill >= 0.09f && fill <= 0.62f) score += 24;
        else score -= 38;

        if (avgSignal > 110) score += 24;
        if (avgMotion > 15) score += 34;
        if (avgEdge > 24) score += 12;

        score += symmetryScore(hot, gridW, gridH, minX, minY, maxX, maxY);
        score += organicRowScore(hot, gridW, gridH, minX, minY, maxX, maxY);

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;

        // furniture rejection
        if (bw > gridW * 0.44f && bh < gridH * 0.16f) score -= 60;
        if (bh > gridH * 0.56f && bw > gridW * 0.40f) score -= 45;

        // too bright/flat usually means wall/light/door
        if (avgGray > 220 && avgMotion < 18) score -= 35;

        return score;
    }

    private static int symmetryScore(boolean[] hot, int gridW, int gridH, int minX, int minY, int maxX, int maxY) {
        int bw = maxX - minX + 1;
        if (bw <= 2) return 0;

        int pairs = 0;
        int hits = 0;

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

        if (s > 0.62f) return 28;
        if (s > 0.42f) return 16;
        return 0;
    }

    private static int organicRowScore(boolean[] hot, int gridW, int gridH, int minX, int minY, int maxX, int maxY) {
        int activeRows = 0;
        int rowChanges = 0;
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

                if (prevWidth >= 0 && Math.abs(width - prevWidth) > 1) rowChanges++;

                prevWidth = width;
                activeRows++;
            }
        }

        if (activeRows < 3) return 0;

        float v = rowChanges / (float)activeRows;

        if (v > 0.33f && v < 0.88f) return 28;
        if (v > 0.18f) return 12;
        return -22;
    }

    private static void drawSpiritHumanLock(int[] out, int w, int h, int x0, int y0, int x1, int y1, int score, boolean wallTrace) {
        int color = wallTrace ? argb(255, 255, 255) : argb(255, 80, 35);
        int core = wallTrace ? argb(30, 205, 255) : argb(255, 160, 55);

        int cx = (x0 + x1) / 2;
        int height = y1 - y0;
        int width = x1 - x0;

        int headR = clamp(height / 10, 5, 24);
        int headY = y0 + height / 7;
        int chestY = y0 + height * 42 / 100;
        int hipY = y0 + height * 68 / 100;

        drawRectCorners(out, w, h, x0, y0, x1, y1, color, score > 165 ? 5 : 3);

        drawCircle(out, w, h, cx, headY, headR, core, 3);
        drawLine(out, w, h, cx, headY + headR, cx, hipY, core, 3);

        drawLine(out, w, h, cx - width / 3, chestY, cx + width / 3, chestY, core, 3);

        drawLine(out, w, h, cx - width / 3, chestY, cx - width / 2, hipY, core, 2);
        drawLine(out, w, h, cx + width / 3, chestY, cx + width / 2, hipY, core, 2);

        drawLine(out, w, h, cx, hipY, cx - width / 3, y1 - 4, core, 3);
        drawLine(out, w, h, cx, hipY, cx + width / 3, y1 - 4, core, 3);
    }

    private static void drawAnimalLock(int[] out, int w, int h, int x0, int y0, int x1, int y1, int score, boolean wallTrace) {
        int color = wallTrace ? argb(220, 255, 255) : argb(255, 170, 45);
        int core = wallTrace ? argb(40, 220, 255) : argb(255, 210, 80);

        int width = x1 - x0;
        int height = y1 - y0;
        int cy = (y0 + y1) / 2;

        int body0 = x0 + width / 5;
        int body1 = x1 - width / 5;

        drawRectCorners(out, w, h, x0, y0, x1, y1, color, score > 165 ? 5 : 3);

        drawLine(out, w, h, body0, cy, body1, cy, core, 4);
        drawCircle(out, w, h, x1 - width / 8, cy - height / 5, Math.max(4, height / 8), core, 2);

        drawLine(out, w, h, body0, cy, x0 + width / 7, y1 - 4, core, 3);
        drawLine(out, w, h, body1, cy, x1 - width / 7, y1 - 4, core, 3);
    }

    private static void drawSubtleWorldEdges(int[] out, int[] edge, int w, int h) {
        int n = w * h;

        for (int i = 0; i < n; i++) {
            if (edge[i] > 175) {
                int r = (out[i] >> 16) & 0xFF;
                int g = (out[i] >> 8) & 0xFF;
                int b = out[i] & 0xFF;

                out[i] = argb(clamp(r + 12), clamp(g + 26), clamp(b + 42));
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
            out[i] = clamp((d - 4) * 7);
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
        int lenX = Math.max(14, (x1 - x0) / 4);
        int lenY = Math.max(14, (y1 - y0) / 4);

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

    private static void ensureMemory(int n) {
        if (background == null || background.length != n) {
            background = new float[n];
            frameCount = 0;
        }

        if (entityMemory == null || entityMemory.length != n) {
            entityMemory = new float[n];
        }

        if (wallTraceMemory == null || wallTraceMemory.length != n) {
            wallTraceMemory = new float[n];
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
