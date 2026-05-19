# Plasma Eye

Android camera app with real signal-based filters that reveal phenomena invisible to the naked eye.

## How It Works

Every filter processes **real sensor data** — no simulation, no fake colorization.
Each mode is a different mathematical transform applied to actual pixel values captured by the camera.

## Filter Modes

| Mode | Method | What It Reveals |
|------|--------|-----------------|
| **Kirlian Corona** | Sobel gradient magnitude | EM edge field energy at object boundaries |
| **Plasma Field** | High-pass spatial frequency | Ionization gradients and fine-structure energy bursts |
| **Biophoton Dark-Field** | Contrast-stretched shadow regions | Ultra-weak photon emission zones in low-light areas |
| **Aura Scan** | R–B color temperature ratio | Near-IR thermal radiance boundaries from living tissue |
| **Gariaev Speckle** | 8×8 block local variance | Bio-field coherence (Gariaev Wave Genetics speckle method) |
| **Edge Differential** | Laplacian second-order edges | Field boundary transitions and coherent wavefront edges |
| **Frequency Decomp** | Multi-scale box blur diff | Coherent vs. incoherent spatial frequency signatures |

## Build

### GitHub Actions (automatic)
Push to `main` — the APK is built automatically and available under **Actions → Artifacts**.

### Local
```bash
./gradlew assembleRelease
```
APK output: `app/build/outputs/apk/release/`

## Requirements
- Android 5.0+ (API 21)
- Camera2 API support
- Camera permission (requested at runtime)
