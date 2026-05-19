package com.plasmacam;

public class PlasmaEngine {

    public static final int MODE_KIRLIAN   = 0;
    public static final int MODE_PLASMA    = 1;
    public static final int MODE_BIOPHOTON = 2;
    public static final int MODE_AURA      = 3;
    public static final int MODE_GARIAEV   = 4;
    public static final int MODE_EDGE_DIFF = 5;
    public static final int MODE_FREQUENCY = 6;

    /**
     * @param sensitivity 0–100: detection threshold — lower = pick up weaker signals
     * @param amplify     0–100: output boost — higher = more vivid/bright rendering
     */
    public static int[] apply(int[] px, int w, int h, int mode, int sensitivity, int amplify) {
        switch (mode) {
            case MODE_KIRLIAN:   return kirlian(px, w, h, sensitivity, amplify);
            case MODE_PLASMA:    return plasma(px, w, h, sensitivity, amplify);
            case MODE_BIOPHOTON: return biophoton(px, w, h, sensitivity, amplify);
            case MODE_AURA:      return aura(px, w, h, amplify);
            case MODE_GARIAEV:   return gariaev(px, w, h, sensitivity, amplify);
            case MODE_EDGE_DIFF: return edgeDiff(px, w, h, sensitivity, amplify);
            case MODE_FREQUENCY: return frequency(px, w, h, amplify);
            default:             return px;
        }
    }

    // ── Kirlian Corona ──────────────────────────────────────────────────────────
    private static int[] kirlian(int[] px, int w, int h, int sensitivity, int amplify) {
        int[] gray = toGray(px, w, h);
        int[] edge = sobel(gray, w, h);
        // sensitivity: lower threshold = weaker edges become visible
        int thresh = (100 - sensitivity);           // 0–100 → lower sens = higher thresh
        float boost = 1.0f + amplify / 25.0f;      // 1.0–5.0x
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int e = Math.max(0, edge[i] - thresh);
            int v = clamp((int)(e * boost));
            int r = clamp(v * 2 - 100);
            int g = clamp(v / 2);
            int b = clamp(v + 60);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ── Plasma Field ────────────────────────────────────────────────────────────
    private static int[] plasma(int[] px, int w, int h, int sensitivity, int amplify) {
        int[] gray    = toGray(px, w, h);
        int[] blurred = boxBlur(gray, w, h, 3);
        float sens  = 1.0f + sensitivity / 20.0f;  // 1.0–6.0x high-pass gain
        float boost = 1.0f + amplify / 25.0f;
        int[] out   = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int hp = clamp((int)(128 + (gray[i] - blurred[i]) * sens * boost));
            int[] c = plasmaMap(hp);
            out[i] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        }
        return out;
    }

    // ── Biophoton Dark-Field ────────────────────────────────────────────────────
    private static int[] biophoton(int[] px, int w, int h, int sensitivity, int amplify) {
        int[] gray = toGray(px, w, h);
        // sensitivity: controls how deep into shadows we look
        int shadowCut = (100 - sensitivity) * 2; // 0–200
        float boost   = 1.0f + amplify / 20.0f;
        int min = 255, max = 0;
        for (int v : gray) { if (v < min) min = v; if (v > max) max = v; }
        int range = Math.max(1, max - min);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int stretched = (gray[i] - min) * 255 / range;
            int inv = 255 - stretched;
            int signal = Math.max(0, inv - shadowCut);
            int v = clamp((int)(signal * boost));
            int r = clamp(v > 180 ? (v - 180) * 5 : 0);
            int g = clamp(v > 80  ? (v - 80)  * 2 : 0);
            int b = clamp(stretched / 2 + 30);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ── Aura / Near-Field Radiance ───────────────────────────────────────────────
    private static int[] aura(int[] px, int w, int h, int amplify) {
        float boost = 1.0f + amplify / 25.0f;
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8)  & 0xFF;
            int b =  px[i]        & 0xFF;
            float warmth = (r - b) / 255.0f;
            float lum    = (r + g + b) / 765.0f;
            int[] c = auraMap(warmth * boost, lum);
            out[i] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        }
        return out;
    }

    // ── Gariaev Laser Speckle Coherence ─────────────────────────────────────────
    private static int[] gariaev(int[] px, int w, int h, int sensitivity, int amplify) {
        int[] gray = toGray(px, w, h);
        // sensitivity controls coherence threshold — higher = only very ordered regions glow
        float cohThresh = sensitivity * 2.0f;      // 0–200
        float boost     = 1.0f + amplify / 25.0f;
        int[] out  = new int[w * h];
        int bs = 8;
        for (int by = 0; by < h; by += bs) {
            for (int bx = 0; bx < w; bx += bs) {
                int sum = 0, sumSq = 0, cnt = 0;
                for (int dy = 0; dy < bs && by + dy < h; dy++)
                    for (int dx = 0; dx < bs && bx + dx < w; dx++) {
                        int v = gray[(by + dy) * w + (bx + dx)];
                        sum += v; sumSq += v * v; cnt++;
                    }
                float mean = sum / (float) cnt;
                float var  = sumSq / (float) cnt - mean * mean;
                int coherence = clamp((int)((255 - var / 8) * boost));
                coherence = Math.max(0, coherence - (int)cohThresh);
                int[] c = speckleMap(clamp(coherence));
                for (int dy = 0; dy < bs && by + dy < h; dy++)
                    for (int dx = 0; dx < bs && bx + dx < w; dx++)
                        out[(by + dy) * w + (bx + dx)] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
            }
        }
        return out;
    }

    // ── Edge Differential (Laplacian) ───────────────────────────────────────────
    private static int[] edgeDiff(int[] px, int w, int h, int sensitivity, int amplify) {
        int[] gray = toGray(px, w, h);
        int thresh = (100 - sensitivity);
        float boost = 1.0f + amplify / 20.0f;
        int[] out  = new int[w * h];
        int[] lap  = new int[]{0, -1, 0, -1, 4, -1, 0, -1, 0};
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int sum = 0;
                for (int ky = -1; ky <= 1; ky++)
                    for (int kx = -1; kx <= 1; kx++)
                        sum += gray[(y + ky) * w + (x + kx)] * lap[(ky + 1) * 3 + (kx + 1)];
                int v = clamp((int)(Math.max(0, Math.abs(sum) - thresh) * boost));
                int r = clamp(v * 2);
                int g = clamp(v);
                int b = clamp(v / 3);
                out[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    // ── Spatial Frequency Decomposition ─────────────────────────────────────────
    private static int[] frequency(int[] px, int w, int h, int amplify) {
        int[] gray = toGray(px, w, h);
        int[] lo   = boxBlur(gray, w, h, 5);
        int[] lo2  = boxBlur(lo,   w, h, 5);
        float boost = 1.0f + amplify / 25.0f;
        int[] out  = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int hi  = clamp((int)(128 + (gray[i] - lo[i])  * 3 * boost));
            int mid = clamp((int)(128 + (lo[i]  - lo2[i]) * 3 * boost));
            int r = clamp((lo2[i] - 100) * 2);
            int g = clamp((mid - 100) * 2);
            int b = clamp((hi  - 100) * 2);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static int[] toGray(int[] px, int w, int h) {
        int[] g = new int[w * h];
        for (int i = 0; i < g.length; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int gv = (px[i] >> 8) & 0xFF;
            int b =  px[i]        & 0xFF;
            g[i] = (r * 77 + gv * 150 + b * 29) >> 8;
        }
        return g;
    }

    private static int[] sobel(int[] g, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int gx = -g[(y-1)*w+(x-1)] - 2*g[y*w+(x-1)] - g[(y+1)*w+(x-1)]
                         +g[(y-1)*w+(x+1)] + 2*g[y*w+(x+1)] + g[(y+1)*w+(x+1)];
                int gy = -g[(y-1)*w+(x-1)] - 2*g[(y-1)*w+x] - g[(y-1)*w+(x+1)]
                         +g[(y+1)*w+(x-1)] + 2*g[(y+1)*w+x] + g[(y+1)*w+(x+1)];
                out[y * w + x] = clamp((int) Math.sqrt(gx * gx + gy * gy) / 4);
            }
        }
        return out;
    }

    private static int[] boxBlur(int[] g, int w, int h, int r) {
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, cnt = 0;
                for (int dx = -r; dx <= r; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < w) { s += g[y * w + nx]; cnt++; }
                }
                tmp[y * w + x] = s / cnt;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, cnt = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int ny = y + dy;
                    if (ny >= 0 && ny < h) { s += tmp[ny * w + x]; cnt++; }
                }
                out[y * w + x] = s / cnt;
            }
        }
        return out;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int[] plasmaMap(int v) {
        if (v < 64)  return new int[]{0,           v * 2,       128 + v};
        if (v < 128) return new int[]{(v-64)*2,    128,         255};
        if (v < 192) return new int[]{128+(v-128), 255-(v-128), 255-(v-128)*2};
        return new int[]{255, 255-(v-192)*4, 0};
    }

    private static int[] auraMap(float warmth, float lum) {
        int r = clamp((int)((warmth + 1) * 127 + lum * 60));
        int g = clamp((int)(lum * 200));
        int b = clamp((int)((1 - warmth) * 127 + lum * 40));
        return new int[]{r, g, b};
    }

    private static int[] speckleMap(int coherence) {
        if (coherence > 200) return new int[]{255, 255, coherence};
        if (coherence > 128) return new int[]{255, coherence, 0};
        if (coherence > 64)  return new int[]{coherence * 2, 0, 0};
        return new int[]{20, 0, 30};
    }
}
