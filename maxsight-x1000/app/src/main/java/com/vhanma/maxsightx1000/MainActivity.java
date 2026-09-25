package com.vhanma.maxsightx1000;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.LiveData;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private PreviewView preview;
    private TextView status, evLabel;
    private LinearLayout advancedRow, exposureRow, presetRow;
    private SeekBar zoomSeek;
    private Button recBtn, collapseBtn, detailBtn, steadyBtn, teleBtn;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private float effectiveZoom = 1f;
    private float nativeZoom = 1f;
    private float nativeMax = 1f;
    private float deepFactor = 1f;
    private float recordingDeepFactor = 1f;

    private int evIndex = 0;
    private int startupRetries = 0;
    private boolean detailMode = true;
    private boolean steadyMode = false;
    private boolean teleLock = false;
    private boolean collapsed = false;
    private boolean cameraReady = false;
    private boolean cameraStarting = false;
    private boolean pendingRecordAfterAudioPermission = false;
    private boolean videoMode = false;

    private File rawRecording;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    startCamera();
                } else {
                    cameraStarting = false;
                    updateStatus("CAMERA permission is required");
                }
            });

    private final ActivityResultLauncher<String> audioPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (pendingRecordAfterAudioPermission) {
                    pendingRecordAfterAudioPermission = false;
                    beginRecording();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(R.layout.activity_main);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupControls();
        requestCameraOrStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && !cameraReady && !cameraStarting && recording == null) {
            if (cameraProvider != null) {
                bindPhotoMode();
            } else {
                startCamera();
            }
        }
    }

    private void bindViews() {
        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        evLabel = findViewById(R.id.evLabel);
        advancedRow = findViewById(R.id.advancedRow);
        exposureRow = findViewById(R.id.exposureRow);
        presetRow = findViewById(R.id.presetRow);
        zoomSeek = findViewById(R.id.zoomSeek);
        recBtn = findViewById(R.id.recBtn);
        collapseBtn = findViewById(R.id.collapseBtn);
        detailBtn = findViewById(R.id.detailBtn);
        steadyBtn = findViewById(R.id.steadyBtn);
        teleBtn = findViewById(R.id.teleBtn);

        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
    }

    private void setupControls() {
        float[] presets = {1, 5, 10, 25, 50, 100, 250, 500, 1000};
        for (float p : presets) {
            Button b = new Button(this);
            b.setText(((int) p) + "×");
            b.setTextSize(11f);
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundResource(R.drawable.btn_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(42));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> setEffectiveZoom(p));
            presetRow.addView(b);
        }

        zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && recording == null) {
                    setEffectiveZoom((float) Math.exp((progress / 10000.0) * Math.log(1000.0)));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (recording == null) {
                    setEffectiveZoom(effectiveZoom * d.getScaleFactor());
                }
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                focusAt(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                setEffectiveZoom(effectiveZoom < 10f ? 10f : 1f);
                return true;
            }
        });

        preview.setOnTouchListener((v, e) -> {
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            return true;
        });

        findViewById(R.id.photoBtn).setOnClickListener(v -> takePhoto());
        recBtn.setOnClickListener(v -> toggleRecording());
        collapseBtn.setOnClickListener(v -> toggleCollapse());

        detailBtn.setOnClickListener(v -> {
            detailMode = true;
            steadyMode = false;
            refreshModeButtons();
            rebuildCamera();
        });

        steadyBtn.setOnClickListener(v -> {
            steadyMode = true;
            detailMode = false;
            refreshModeButtons();
            rebuildCamera();
        });

        teleBtn.setOnClickListener(v -> {
            teleLock = !teleLock;
            teleBtn.setText(teleLock ? "TELE LOCK ✓" : "TELE LOCK");
            if (teleLock && effectiveZoom < 5f) setEffectiveZoom(5f);
            updateStatus("Tele lock " + (teleLock ? "armed" : "released"));
        });

        findViewById(R.id.evMinus).setOnClickListener(v -> adjustExposure(-1));
        findViewById(R.id.evPlus).setOnClickListener(v -> adjustExposure(1));
        refreshModeButtons();
    }

    private void requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            updateStatus("Waiting for camera permission…");
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        if (cameraStarting) return;

        cameraStarting = true;
        cameraReady = false;
        updateStatus("Opening camera…");

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraStarting = false;
                startupRetries = 0;
                bindPhotoMode();
            } catch (Exception e) {
                cameraStarting = false;
                updateStatus("Camera provider error: " + safeMessage(e));
                scheduleStartupRetry();
            }
        }, ContextCompat.getMainExecutor(this));

        mainHandler.postDelayed(() -> {
            if (!cameraReady && cameraProvider == null && cameraStarting) {
                cameraStarting = false;
                updateStatus("Camera startup timed out • retrying…");
                scheduleStartupRetry();
            }
        }, 6500);
    }

    private void scheduleStartupRetry() {
        if (startupRetries >= 2) {
            updateStatus("Camera startup failed • close any other camera app and reopen MaxSight");
            return;
        }
        startupRetries++;
        mainHandler.postDelayed(this::startCamera, 650);
    }

    private CameraSelector chooseCamera() throws Exception {
        if (cameraProvider != null && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        if (cameraProvider != null && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            return CameraSelector.DEFAULT_FRONT_CAMERA;
        }
        throw new IllegalStateException("No usable camera found");
    }

    private Preview makePreview(boolean stabilization) {
        Preview.Builder pb = new Preview.Builder();
        if (stabilization) pb.setPreviewStabilizationEnabled(true);
        Preview p = pb.build();
        p.setSurfaceProvider(preview.getSurfaceProvider());
        return p;
    }

    private void bindPhotoMode() {
        if (cameraProvider == null || recording != null) return;
        cameraReady = false;
        videoMode = false;
        updateStatus("Starting live camera…");

        try {
            bindPhotoUseCases(steadyMode);
        } catch (Exception first) {
            if (steadyMode) {
                try {
                    bindPhotoUseCases(false);
                    updateStatus("Live camera ready • stabilization fallback");
                    return;
                } catch (Exception ignored) {
                    // Continue into preview-only fallback.
                }
            }
            try {
                bindPreviewOnly();
                updateStatus("Live camera ready • compatibility mode");
            } catch (Exception second) {
                cameraReady = false;
                updateStatus("Camera bind error: " + safeMessage(second));
            }
        }
    }

    private void bindPhotoUseCases(boolean stabilization) throws Exception {
        cameraProvider.unbindAll();
        Preview p = makePreview(stabilization);
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();
        videoCapture = null;
        camera = cameraProvider.bindToLifecycle(this, chooseCamera(), p, imageCapture);
        onCameraBound();
    }

    private void bindPreviewOnly() throws Exception {
        cameraProvider.unbindAll();
        Preview p = makePreview(false);
        imageCapture = null;
        videoCapture = null;
        camera = cameraProvider.bindToLifecycle(this, chooseCamera(), p);
        onCameraBound();
    }

    private void onCameraBound() {
        cameraReady = true;
        cameraStarting = false;
        LiveData<ZoomState> zoomState = camera.getCameraInfo().getZoomState();
        zoomState.observe(this, state -> {
            if (state != null) {
                nativeMax = Math.max(1f, state.getMaxZoomRatio());
                preview.post(this::applyZoom);
            }
        });
        preview.post(this::applyZoom);
        refreshModeButtons();
        updateStatus(videoMode ? "Video camera ready" : "Camera ready");
    }

    private void rebuildCamera() {
        if (recording == null && cameraProvider != null) {
            bindPhotoMode();
        }
    }

    private void setEffectiveZoom(float z) {
        float min = teleLock ? 5f : 1f;
        effectiveZoom = Math.max(min, Math.min(1000f, z));
        zoomSeek.setProgress((int) Math.round(Math.log(effectiveZoom) / Math.log(1000.0) * 10000));
        applyZoom();
    }

    private void applyZoom() {
        if (camera == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) return;

        nativeZoom = Math.max(1f, Math.min(effectiveZoom, nativeMax));
        deepFactor = Math.max(1f, effectiveZoom / nativeZoom);
        camera.getCameraControl().setZoomRatio(nativeZoom);

        preview.setPivotX(preview.getWidth() / 2f);
        preview.setPivotY(preview.getHeight() / 2f);
        preview.setScaleX(deepFactor);
        preview.setScaleY(deepFactor);
        updateStatus(null);
    }

    private void focusAt(float x, float y) {
        if (camera == null || !cameraReady) return;
        MeteringPointFactory factory = preview.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build();
        camera.getCameraControl().startFocusAndMetering(action);
        updateStatus("AF/AE target locked");
    }

    private void adjustExposure(int delta) {
        if (camera == null || !cameraReady) return;
        int min = camera.getCameraInfo().getExposureState().getExposureCompensationRange().getLower();
        int max = camera.getCameraInfo().getExposureState().getExposureCompensationRange().getUpper();
        evIndex = Math.max(min, Math.min(max, evIndex + delta));
        camera.getCameraControl().setExposureCompensationIndex(evIndex);
        evLabel.setText("EV " + (evIndex > 0 ? "+" : "") + evIndex);
        updateStatus("Exposure adjusted");
    }

    private void takePhoto() {
        if (!cameraReady) {
            toast("Camera is still starting");
            return;
        }
        if (imageCapture == null) {
            toast("Restoring photo mode…");
            bindPhotoMode();
            return;
        }

        File raw = new File(getCacheDir(), "maxsight_raw_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(raw).build();
        final float factor = deepFactor;

        imageCapture.takePicture(opts, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                try {
                    Bitmap src = loadUpright(raw);
                    if (src == null) throw new IOException("decode failed");
                    Bitmap out = deepCropAndEnhance(src, factor);
                    saveBitmapToGallery(out);
                    if (out != src) out.recycle();
                    src.recycle();
                    raw.delete();
                    runOnUiThread(() -> toast("PHOTO SAVED • " + formatZoom(effectiveZoom)));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Photo error: " + safeMessage(e)));
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> toast("Photo capture error: " + safeMessage(exception)));
            }
        });
    }

    private Bitmap loadUpright(File file) throws IOException {
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (b == null) return null;
        ExifInterface exif = new ExifInterface(file);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int degrees = 0;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        if (degrees == 0) return b;

        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private Bitmap deepCropAndEnhance(Bitmap src, float factor) {
        if (factor <= 1.001f) return src;

        int sw = src.getWidth();
        int sh = src.getHeight();
        int cw = Math.max(2, Math.round(sw / factor));
        int ch = Math.max(2, Math.round(sh / factor));
        int left = Math.max(0, (sw - cw) / 2);
        int top = Math.max(0, (sh - ch) / 2);

        Bitmap crop = Bitmap.createBitmap(src, left, top, Math.min(cw, sw - left), Math.min(ch, sh - top));
        Bitmap scaled = Bitmap.createScaledBitmap(crop, sw, sh, true);
        if (crop != src) crop.recycle();
        if (!detailMode) return scaled;

        Bitmap boosted = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(boosted);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.08f);
        ColorMatrix contrast = new ColorMatrix(new float[]{
                1.12f, 0, 0, 0, -15,
                0, 1.12f, 0, 0, -15,
                0, 0, 1.12f, 0, -15,
                0, 0, 0, 1, 0
        });
        cm.postConcat(contrast);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(scaled, 0, 0, paint);
        scaled.recycle();
        return boosted;
    }

    private void toggleRecording() {
        if (recording != null) {
            recording.stop();
            return;
        }
        if (!cameraReady || cameraProvider == null) {
            toast("Camera is still starting");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecordAfterAudioPermission = true;
            audioPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        beginRecording();
    }

    private void beginRecording() {
        if (cameraProvider == null || recording != null) return;
        recordingDeepFactor = deepFactor;
        cameraReady = false;
        videoMode = true;
        updateStatus("Switching to video engine…");

        try {
            bindVideoUseCases(detailMode ? Quality.UHD : Quality.FHD, steadyMode);
        } catch (Exception first) {
            try {
                bindVideoUseCases(Quality.FHD, false);
                updateStatus("Video compatibility fallback • FHD");
            } catch (Exception second) {
                videoMode = false;
                updateStatus("Video bind error: " + safeMessage(second));
                bindPhotoMode();
                return;
            }
        }

        startBoundRecording();
    }

    private void bindVideoUseCases(Quality quality, boolean stabilization) throws Exception {
        cameraProvider.unbindAll();
        Preview p = makePreview(stabilization);
        QualitySelector selector = QualitySelector.from(
                quality,
                androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD));
        Recorder recorder = new Recorder.Builder().setQualitySelector(selector).build();
        VideoCapture.Builder<Recorder> vb = new VideoCapture.Builder<>(recorder);
        if (stabilization) vb.setVideoStabilizationEnabled(true);
        videoCapture = vb.build();
        imageCapture = null;
        camera = cameraProvider.bindToLifecycle(this, chooseCamera(), p, videoCapture);
        onCameraBound();
    }

    private void startBoundRecording() {
        if (videoCapture == null) {
            updateStatus("Video engine unavailable");
            bindPhotoMode();
            return;
        }

        rawRecording = new File(getCacheDir(), "maxsight_raw_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(rawRecording).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, opts);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recBtn.setText("STOP");
                recBtn.setTextColor(0xFFFFFFFF);
                updateStatus("REC LOCK • deep crop fixed at " + formatZoom(effectiveZoom));
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
                recording = null;
                recBtn.setText("REC");
                recBtn.setTextColor(0xFFFF4A5C);
                videoMode = false;
                bindPhotoMode();

                if (f.hasError()) {
                    updateStatus("Recording error " + f.getError());
                    if (rawRecording != null) rawRecording.delete();
                } else {
                    exportDeepVideo(rawRecording, recordingDeepFactor);
                }
            }
        });
    }

    private void exportDeepVideo(File input, float factor) {
        updateStatus("DEEP EXPORT • processing saved video…");
        File out = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "MaxSight_x1000_" + System.currentTimeMillis() + ".mp4");
        if (out.getParentFile() != null) out.getParentFile().mkdirs();

        List<Effect> videoEffects = new ArrayList<>();
        if (factor > 1.001f) {
            float edge = Math.max(0.001f, Math.min(1f, 1f / factor));
            videoEffects.add(new Crop(-edge, edge, -edge, edge));
            videoEffects.add(Presentation.createForHeight(detailMode ? 2160 : 1080));
        }

        Effects effects = new Effects(Collections.emptyList(), videoEffects);
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(effects)
                .build();

        Transformer transformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult result) {
                        try {
                            copyVideoToGallery(out);
                            input.delete();
                            out.delete();
                            updateStatus("VIDEO SAVED • deep export complete");
                            toast("VIDEO SAVED");
                        } catch (Exception e) {
                            updateStatus("Gallery copy error: " + safeMessage(e));
                        }
                    }

                    @Override
                    public void onError(@NonNull Composition composition,
                                        @NonNull ExportResult result,
                                        @NonNull ExportException exception) {
                        updateStatus("Deep export error: " + safeMessage(exception));
                        try {
                            copyVideoToGallery(input);
                            toast("RAW VIDEO SAVED");
                        } catch (Exception ignored) {}
                    }
                })
                .build();

        transformer.start(item, out.getAbsolutePath());
    }

    private Uri saveBitmapToGallery(Bitmap bitmap) throws IOException {
        String name = "MaxSight_x1000_" + System.currentTimeMillis() + ".jpg";
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MaxSightX1000");
        }
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                throw new IOException("JPEG write failed");
            }
        }
        return uri;
    }

    private Uri copyVideoToGallery(File file) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "MaxSight_x1000_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MaxSightX1000");
        }
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (InputStream in = new FileInputStream(file); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("video output failed");
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
        }
        return uri;
    }

    private void refreshModeButtons() {
        detailBtn.setText(detailMode ? "DETAIL UHD ✓" : "DETAIL UHD");
        steadyBtn.setText(steadyMode ? "STEADY FHD ✓" : "STEADY FHD");
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        presetRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        zoomSeek.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        advancedRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        exposureRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapseBtn.setText(collapsed ? "▲" : "▼");
    }

    private void updateStatus(String note) {
        runOnUiThread(() -> {
            String mode = detailMode ? "DETAIL-UHD" : (steadyMode ? "STEADY-FHD" : "STANDARD");
            String base = String.format(Locale.US,
                    "%s  |  %s  | native %.1f×/%.1f×  | deep %.1f×",
                    formatZoom(effectiveZoom), mode, nativeZoom, nativeMax, deepFactor);
            status.setText(note == null ? base : note + "\n" + base);
        });
    }

    private String formatZoom(float z) {
        return z < 10 ? String.format(Locale.US, "%.1f×", z) : String.format(Locale.US, "%.0f×", z);
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (recording != null) recording.stop();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
