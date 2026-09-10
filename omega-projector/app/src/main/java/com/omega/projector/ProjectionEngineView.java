package com.omega.projector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProjectionEngineView extends FrameLayout {
    public static final int MODE_WALL = 0;
    public static final int MODE_LENS = 1;
    public static final int MODE_HOLO4 = 2;
    public static final int MODE_GHOST = 3;

    public interface StatusListener { void onStatus(String status); }

    public static final class Snapshot {
        int mode;
        float brightness, contrast, topTaper, bottomTaper, skew, zoom, holoSize;
        boolean mirror, muted;
    }

    private final TextureView videoView;
    private final ImageView imageView;
    private final HoloCanvasView holoView;
    private final TextView emptyView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();

    private MediaPlayer player;
    private Surface playerSurface;
    private Uri mediaUri;
    private boolean mediaIsVideo;
    private Bitmap imageBitmap;
    private StatusListener statusListener;

    private int mode = MODE_WALL;
    private float brightness = 1f;
    private float contrast = 1f;
    private float topTaper = 0f;
    private float bottomTaper = 0f;
    private float skew = 0f;
    private float zoom = 1f;
    private float holoSize = 0.82f;
    private boolean mirror = false;
    private boolean muted = false;
    private int videoWidth = 16, videoHeight = 9;
    private boolean samplerRunning = false;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!samplerRunning) return;
            if ((mode == MODE_HOLO4 || mode == MODE_GHOST) && mediaIsVideo && videoView.isAvailable()) {
                try {
                    int vw = Math.max(180, Math.min(480, getWidth() / 2));
                    int vh = Math.max(120, Math.min(480, getHeight() / 2));
                    Bitmap b = videoView.getBitmap(vw, vh);
                    if (b != null) holoView.setFrame(b);
                } catch (Throwable ignored) {}
            }
            handler.postDelayed(this, 50);
        }
    };

    public ProjectionEngineView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        videoView = new TextureView(context);
        videoView.setOpaque(true);
        addView(videoView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        imageView = new ImageView(context);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        addView(imageView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        holoView = new HoloCanvasView(context);
        holoView.setVisibility(View.GONE);
        addView(holoView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("Ω\nLOAD MEDIA OR TAP TEST");
        emptyView.setTextColor(Color.rgb(110, 225, 255));
        emptyView.setTextSize(22);
        emptyView.setTypeface(Typeface.DEFAULT_BOLD);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setBackgroundColor(Color.BLACK);
        addView(emptyView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                attachSurface(st);
                if (mediaIsVideo && mediaUri != null) prepareVideo();
                updateTransforms();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { updateTransforms(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (playerSurface != null) { playerSurface.release(); playerSurface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        setTestPattern();
    }

    public void setStatusListener(StatusListener listener) { statusListener = listener; }
    private void status(String s) { if (statusListener != null) statusListener.onStatus(s); }

    private void attachSurface(SurfaceTexture st) {
        if (playerSurface != null) playerSurface.release();
        playerSurface = new Surface(st);
    }

    public void setMedia(Uri uri, boolean isVideo) {
        mediaUri = uri;
        mediaIsVideo = isVideo;
        emptyView.setVisibility(View.GONE);
        releasePlayer();
        if (isVideo) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            if (videoView.isAvailable()) {
                if (playerSurface == null) attachSurface(videoView.getSurfaceTexture());
                prepareVideo();
            } else {
                status("VIDEO WAITING FOR DISPLAY SURFACE");
            }
        } else {
            videoView.setVisibility(View.GONE);
            status("LOADING IMAGE…");
            decodeImage(uri);
        }
        updateModeVisibility();
    }

    public void setTestPattern() {
        releasePlayer();
        mediaUri = null;
        mediaIsVideo = false;
        Bitmap b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(Color.BLACK);
        int[] cols = new int[]{Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.WHITE};
        float stripe = b.getWidth() / (float) cols.length;
        for (int i=0;i<cols.length;i++) { p.setColor(cols[i]); c.drawRect(i*stripe,0,(i+1)*stripe,180,p); }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(8); p.setColor(Color.rgb(80,220,255));
        c.drawRect(20,20,b.getWidth()-20,b.getHeight()-20,p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(86);
        c.drawText("Ω PROJECTOR", b.getWidth()/2f, 335, p);
        p.setTextSize(42); p.setColor(Color.rgb(100,225,255));
        c.drawText("VIDEO PATH TEST • WALL • HOLO", b.getWidth()/2f, 405, p);
        p.setTextSize(30); p.setColor(Color.LTGRAY);
        c.drawText("If you see this, the display engine is alive.", b.getWidth()/2f, 470, p);
        setImageBitmapInternal(b);
        emptyView.setVisibility(View.GONE);
        status("TEST PATTERN VISIBLE");
    }

    private void decodeImage(Uri uri) {
        imageExecutor.execute(() -> {
            Bitmap decoded = null;
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.Source source = ImageDecoder.createSource(getContext().getContentResolver(), uri);
                    decoded = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        int max = 2200;
                        if (Math.max(w,h) > max) {
                            float scale = max / (float)Math.max(w,h);
                            decoder.setTargetSize(Math.max(1,(int)(w*scale)), Math.max(1,(int)(h*scale)));
                        }
                    });
                } else {
                    try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                        decoded = android.graphics.BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName();
                post(() -> status("IMAGE LOAD ERROR: " + msg));
            }
            Bitmap finalDecoded = decoded;
            if (finalDecoded != null) post(() -> { setImageBitmapInternal(finalDecoded); status("IMAGE READY"); });
        });
    }

    private void setImageBitmapInternal(Bitmap bitmap) {
        if (imageBitmap != null && imageBitmap != bitmap && !imageBitmap.isRecycled()) imageBitmap.recycle();
        imageBitmap = bitmap;
        imageView.setImageBitmap(bitmap);
        imageView.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.GONE);
        holoView.setFrame(copyForHolo(bitmap));
        updateTransforms();
        updateModeVisibility();
    }

    private Bitmap copyForHolo(Bitmap source) {
        int max = 720;
        int w = source.getWidth(), h = source.getHeight();
        if (Math.max(w,h) <= max) return source.copy(Bitmap.Config.ARGB_8888, false);
        float s = max / (float)Math.max(w,h);
        return Bitmap.createScaledBitmap(source, Math.max(1,(int)(w*s)), Math.max(1,(int)(h*s)), true);
    }

    private void prepareVideo() {
        if (mediaUri == null || !mediaIsVideo || !videoView.isAvailable()) return;
        releasePlayer();
        try {
            if (playerSurface == null) attachSurface(videoView.getSurfaceTexture());
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(getContext(), mediaUri);
            mp.setSurface(playerSurface);
            mp.setLooping(true);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                p.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                p.start();
                status("VIDEO PLAYING • SURFACE OK");
                startSamplerIfNeeded();
            });
            mp.setOnVideoSizeChangedListener((p,w,h) -> {
                if (w > 0 && h > 0) { videoWidth = w; videoHeight = h; updateTransforms(); }
            });
            mp.setOnInfoListener((p,what,extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status("VIDEO FRAME VISIBLE");
                return false;
            });
            mp.setOnErrorListener((p,what,extra) -> {
                status("VIDEO ERROR " + what + "/" + extra);
                return true;
            });
            status("OPENING VIDEO…");
            mp.prepareAsync();
        } catch (Throwable t) {
            status("VIDEO OPEN ERROR: " + t.getClass().getSimpleName());
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.setSurface(null); } catch (Throwable ignored) {}
            try { player.reset(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    public void releaseMedia() {
        stopSampler();
        releasePlayer();
        if (playerSurface != null) { playerSurface.release(); playerSurface = null; }
        imageExecutor.shutdownNow();
    }

    public void pauseMedia() { if (player != null) try { player.pause(); } catch (Throwable ignored) {} }
    public void resumeMedia() { if (player != null) try { player.start(); } catch (Throwable ignored) {} }
    public void togglePlay() {
        if (player == null) return;
        try { if (player.isPlaying()) { player.pause(); status("PAUSED"); } else { player.start(); status("PLAYING"); } } catch (Throwable ignored) {}
    }

    public boolean isMuted() { return muted; }
    public void setMuted(boolean value) {
        muted = value;
        if (player != null) try { player.setVolume(value ? 0f : 1f, value ? 0f : 1f); } catch (Throwable ignored) {}
    }

    public void setMode(int value) { mode = value; holoView.setMode(value); updateModeVisibility(); updateTransforms(); }
    public int getMode() { return mode; }
    public void setMirror(boolean value) { mirror = value; holoView.setMirror(value); updateTransforms(); }
    public void setBrightness(float value) { brightness = value; holoView.setBrightness(value); applyColorFilter(); }
    public void setContrast(float value) { contrast = value; holoView.setContrast(value); applyColorFilter(); }
    public void setGamma(float value) { /* retained for UI compatibility; direct TextureView path prioritizes reliability */ }
    public void setGlow(float value) { /* hologram glow intentionally omitted from v2 reliability path */ }
    public void setKeyThreshold(float value) { /* reserved for a later GPU-only keyer */ }
    public void setTopTaper(float value) { topTaper = value; updateTransforms(); }
    public void setBottomTaper(float value) { bottomTaper = value; updateTransforms(); }
    public void setSkew(float value) { skew = value; updateTransforms(); }
    public void setZoom(float value) { zoom = value; updateTransforms(); }
    public void setHoloSize(float value) { holoSize = value; holoView.setHoloSize(value); }

    private void applyColorFilter() {
        float c = Math.max(0.2f, contrast);
        float b = (brightness - 1f) * 255f;
        float t = 128f * (1f-c) + b;
        ColorMatrix cm = new ColorMatrix(new float[]{c,0,0,0,t, 0,c,0,0,t, 0,0,c,0,t, 0,0,0,1,0});
        imageView.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private void updateModeVisibility() {
        boolean holo = mode == MODE_HOLO4 || mode == MODE_GHOST;
        holoView.setVisibility(holo ? View.VISIBLE : View.GONE);
        if (holo) {
            holoView.bringToFront();
            if (mediaIsVideo) startSamplerIfNeeded();
            else if (imageBitmap != null) holoView.setFrame(copyForHolo(imageBitmap));
        } else {
            stopSampler();
        }
        emptyView.bringToFront();
        if (emptyView.getVisibility() == View.GONE && holo) holoView.bringToFront();
    }

    private void startSamplerIfNeeded() {
        if (!(mode == MODE_HOLO4 || mode == MODE_GHOST) || !mediaIsVideo) return;
        if (!samplerRunning) { samplerRunning = true; handler.post(sampler); }
    }

    private void stopSampler() {
        samplerRunning = false;
        handler.removeCallbacks(sampler);
    }

    private void updateTransforms() {
        post(() -> {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float rotate = mode == MODE_LENS ? 180f : 0f;

            if (mediaIsVideo) {
                Matrix m = buildVideoMatrix(w,h);
                if (mirror) m.postScale(-1f,1f,w/2f,h/2f);
                if (rotate != 0f) m.postRotate(rotate,w/2f,h/2f);
                videoView.setTransform(m);
            } else if (imageBitmap != null) {
                Matrix m = buildImageMatrix(w,h,imageBitmap.getWidth(),imageBitmap.getHeight());
                if (mirror) m.postScale(-1f,1f,w/2f,h/2f);
                if (rotate != 0f) m.postRotate(rotate,w/2f,h/2f);
                imageView.setImageMatrix(m);
            }
        });
    }

    private Matrix buildVideoMatrix(int w, int h) {
        Matrix m = new Matrix();
        float viewAspect = w / (float)Math.max(1,h);
        float vidAspect = videoWidth / (float)Math.max(1,videoHeight);
        float sx=1f, sy=1f;
        if (vidAspect > viewAspect) sy = viewAspect / vidAspect;
        else sx = vidAspect / viewAspect;
        m.setScale(sx,sy,w/2f,h/2f);
        applyProjectiveWarp(m,w,h);
        return m;
    }

    private Matrix buildImageMatrix(int w, int h, int bw, int bh) {
        float s = Math.min(w/(float)Math.max(1,bw), h/(float)Math.max(1,bh));
        float dw = bw*s, dh = bh*s;
        Matrix m = new Matrix();
        m.setScale(s,s);
        m.postTranslate((w-dw)/2f,(h-dh)/2f);
        m.postScale(zoom,zoom,w/2f,h/2f);
        return m;
    }

    private void applyProjectiveWarp(Matrix m, int w, int h) {
        float ti = Math.min(0.38f, Math.max(0f, topTaper)) * w;
        float bi = Math.min(0.38f, Math.max(0f, bottomTaper)) * w;
        float sk = skew * w;
        float[] src = new float[]{0,0,w,0,w,h,0,h};
        float[] dst = new float[]{ti+sk,0,w-ti+sk,0,w-bi-sk,h,bi-sk,h};
        Matrix warp = new Matrix();
        if (warp.setPolyToPoly(src,0,dst,0,4)) m.postConcat(warp);
        m.postScale(zoom,zoom,w/2f,h/2f);
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.mode=mode; s.brightness=brightness; s.contrast=contrast; s.topTaper=topTaper; s.bottomTaper=bottomTaper;
        s.skew=skew; s.zoom=zoom; s.holoSize=holoSize; s.mirror=mirror; s.muted=muted;
        return s;
    }

    public void applySnapshot(Snapshot s) {
        if (s == null) return;
        setBrightness(s.brightness); setContrast(s.contrast); setTopTaper(s.topTaper); setBottomTaper(s.bottomTaper);
        setSkew(s.skew); setZoom(s.zoom); setHoloSize(s.holoSize); setMirror(s.mirror); setMuted(s.muted); setMode(s.mode);
    }
}
