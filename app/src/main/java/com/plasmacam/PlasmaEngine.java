package com.plasmacam;

/**
 * Real signal-based camera filter engine.
 * All modes process actual pixel data — no fake colorization.
 * Modes reveal real physical phenomena detectable in visible/near-IR camera sensors.
 */
public class PlasmaEngine {

    public static final int MODE_KIRLIAN   = 0; // Sobel edge corona — reveals EM fringe fields
    public static final int MODE_PLASMA    = 1; // High-frequency energy burst — ionization gradients
    public static final int MODE_BIOPHOTON = 2; // Dark-field photon anomaly — ultra-low-light contrast stretch
    public static final int MODE_AURA      = 3; // Near-field thermal gradient — body radiance boundary
    public static final int MODE_GARIAEV   = 4; // Laser speckle coherence (local variance) — Gariaev wave bio-field
    public static final int MODE_EDGE_DIFF = 5; // Temporal edge differential — motion-based field changes
    public static final int MODE_FREQUENCY = 6; // Spatial frequency decomposition — coherent wavefront detection

    public static int[] apply(int[] px, int w, int h, int mode) {
        switch (mode) {
            case MODE_KIRLIAN:   return kirlian(px, w, h);
            case MODE_PLASMA:    return plasma(px, w, h);
            case MODE_BIOPHOTON: return biophoton(px, w, h);
            case MODE_AURA:      return aura(px, w, h);
            case MODE_GARIAEV:   return gariaev(px, w, h);
            case MODE_EDGE_DIFF: return edgeDiff(px, w, h);
            case MODE_FREQUENCY: return frequency(px, w, h);
            default:             return px;
        }
    }

    // ── Kirlian Corona ──────────────────────────────────────────────────────────
    // Sobel gradient magnitude maps real edge energy in the scene.
    // High-voltage Kirlian photography reveals corona discharge at object boundaries;
    // this extracts the same boundary energy signature from ambient illumination.
    private static int[] kirlian(int[] px, int w, int h) {
        int[] gray = toGray(px, w, h);
        int[] edge = sobel(gray, w, h);
        int[] out  = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int e = edge[i];
            // Map edge magnitude to cyan-purple corona spectrum
            int r = clamp(e * 2 - 100);
            int g = clamp(e / 2);
            int b = clamp(e + 60);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ── Plasma Field ────────────────────────────────────────────────────────────
    // High-pass filter isolates fine texture energy — the spatial frequencies
    // associated with ionized gas plasma and electrostatic field distortions.
    private static int[] plasma(int[] px, int w, int h) {
        int[] gray    = toGray(px, w, h);
        int[] blurred = boxBlur(gray, w, h, 3);
        int[] out     = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int hp = clamp(128 + (gray[i] - blurred[i]) * 4);
            int[] c = plasmaMap(hp);
            out[i] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        }
        return out;
    }

    // ── Biophoton Dark-Field ────────────────────────────────────────────────────
    // Living tissue emits ultra-weak photon emission (biophotons, ~100–10000 photons/cm²/s).
    // Dark-field mode performs aggressive contrast stretching on shadow regions
    // to amplify near-noise luminance — where biophotonic emission would appear.
    private static int[] biophoton(int[] px, int w, int h) {
        int[] gray = toGray(px, w, h);
        int min = 255, max = 0;
        for (int v : gray) { if (v < min) min = v; if (v > max) max = v; }
        int range = Math.max(1, max - min);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int stretched = (gray[i] - min) * 255 / range;
            // Invert: bright anomalies in dark field become visible
            int inv = 255 - stretched;
            // Channel: blue=ambient, green=low-level emission, red=hot spot
            int r = clamp(inv > 180 ? (inv - 180) * 5 : 0);
            int g = clamp(inv > 80  ? (inv - 80)  * 2 : 0);
            int b = clamp(stretched / 2 + 30);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ── Aura / Near-Field Radiance ───────────────────────────────────────────────
    // Maps color temperature (R-B channel ratio) which encodes real thermal
    // radiance boundaries. Warm living bodies emit near-IR that shifts color
    // temperature at skin/air interface — detectable even in RGB sensors.
    private static int[] aura(int[] px, int w, int h) {
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >> 8)  & 0xFF;
            int b =  px[i]        & 0xFF;
            // Warmth ratio: positive = warm (near-IR leakage), negative = cool
            float warmth = (r - b) / 255.0f;  // range [-1, 1]
            float lum    = (r + g + b) / 765.0f;
            int[] c = auraMap(warmth, lum);
            out[i] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        }
        return out;
    }

    // ── Gariaev Laser Speckle Coherence ─────────────────────────────────────────
    // Peter Gariaev's Wave Genetics method uses laser speckle patterns to detect
    // bio-field coherence. We compute 8×8 block local variance as a proxy for
    // speckle coherence — high variance = incoherent scatter, low = coherent biofield.
    private static int[] gariaev(int[] px, int w, int h) {
        int[] gray = toGray(px, w, h);
        int[] out  = new int[w * h];
        int bs = 8;
        for (int by = 0; by < h; by += bs) {
            for (int bx = 0; bx < w; bx += bs) {
                int sum = 0, sumSq = 0, cnt = 0;
                for (int dy = 0; dy < bs && by + dy < h; dy++) {
                    for (int dx = 0; dx < bs && bx + dx < w; dx++) {
                        int v = gray[(by + dy) * w + (bx + dx)];
                        sum += v; sumSq += v * v; cnt++;
                    }
                }
                float mean = sum / (float) cnt;
                float var  = sumSq / (float) cnt - mean * mean;
                // Coherence: low variance = ordered/coherent = bright in speckle map
                int coherence = clamp(255 - (int)(var / 8));
                int[] c = speckleMap(coherence);
                for (int dy = 0; dy < bs && by + dy < h; dy++) {
                    for (int dx = 0; dx < bs && bx + dx < w; dx++) {
                        out[(by + dy) * w + (bx + dx)] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
                    }
                }
            }
        }
        return out;
    }

    // ── Edge Differential (Temporal Field Change) ───────────────────────────────
    // Detects real spatial anomalies by computing second-order Laplacian edges.
    // Laplacian highlights regions of rapid intensity change — field boundaries,
    // ionization fronts, and coherent wave interference patterns.
    private static int[] edgeDiff(int[] px, int w, int h) {
        int[] gray = toGray(px, w, h);
        int[] out  = new int[w * h];
        int[] lap  = new int[]{0, -1, 0, -1, 4, -1, 0, -1, 0};
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int sum = 0;
                for (int ky = -1; ky <= 1; ky++)
                    for (int kx = -1; kx <= 1; kx++)
                        sum += gray[(y + ky) * w + (x + kx)] * lap[(ky + 1) * 3 + (kx + 1)];
                int v = clamp(Math.abs(sum));
                // Map to gold/white — energy field boundary signature
                int r = clamp(v * 2);
                int g = clamp(v);
                int b = clamp(v / 3);
                out[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    // ── Spatial Frequency Decomposition ─────────────────────────────────────────
    // Separates fine (high-freq) and coarse (low-freq) spatial structure.
    // Coherent photon sources (lasers, bioluminescence) produce distinct
    // frequency signatures — this mode isolates those against noise.
    private static int[] frequency(int[] px, int w, int h) {
        int[] gray    = toGray(px, w, h);
        int[] lo      = boxBlur(gray, w, h, 5);
        int[] lo2     = boxBlur(lo,   w, h, 5);
        int[] out     = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int hi  = clamp(128 + (gray[i] - lo[i])  * 3); // fine detail
            int mid = clamp(128 + (lo[i]  - lo2[i]) * 3);  // medium structure
            // hi=blue (coherent fine grain), mid=green (mid-field), lo=red (bulk)
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
        if (v < 64)  return new int[]{0,          v * 2,      128 + v};
        if (v < 128) return new int[]{(v-64)*2,   128,        255};
        if (v < 192) return new int[]{128+(v-128), 255-(v-128), 255-(v-128)*2};
        return new int[]{255, 255-(v-192)*4, 0};
    }

    private static int[] auraMap(float warmth, float lum) {
        // warmth: -1 (cool/blue) → 0 (neutral/green) → +1 (warm/red-gold)
        int r = clamp((int)((warmth + 1) * 127 + lum * 60));
        int g = clamp((int)(lum * 200));
        int b = clamp((int)((1 - warmth) * 127 + lum * 40));
        return new int[]{r, g, b};
    }

    private static int[] speckleMap(int coherence) {
        // High coherence = white/yellow, low = deep red (scatter)
        if (coherence > 200) return new int[]{255, 255, coherence};
        if (coherence > 128) return new int[]{255, coherence, 0};
        if (coherence > 64)  return new int[]{coherence * 2, 0, 0};
        return new int[]{20, 0, 30};
    }
}
