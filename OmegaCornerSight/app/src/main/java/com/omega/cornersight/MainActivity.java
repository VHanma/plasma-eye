package com.omega.cornersight;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_CAMERA = 41;

    private TextureView texture;
    private RoiOverlayView roiOverlay;
    private CornerMapView mapView;
    private TextView status;
    private final PenumbraProcessor processor = new PenumbraProcessor();

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private int sensorOrientation = 90;

    private SensorManager sensorManager;
    private Sensor gyro;
    private volatile long unstableUntilNs = 0L;
    private volatile boolean dualMode = false;
    private long lastStatusMs = 0L;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF03070B);

        TextView title = new TextView(this);
        title.setText("Ω CORNERSIGHT");
        title.setTextColor(0xFFB8F6FF);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView guide = new TextView(this);
        guide.setText("Point at the floor or wall BESIDE a corner, not into the hidden area. Drag the cyan box onto that patch, then CALIBRATE while the hidden side is still.");
        guide.setTextColor(0xFF9BB0B8);
        guide.setTextSize(13f);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(14, 0, 14, 8);
        root.addView(guide, new LinearLayout.LayoutParams(-1, dp(58)));

        FrameLayout cameraFrame = new FrameLayout(this);
        cameraFrame.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(surfaceListener);
        cameraFrame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        roiOverlay = new RoiOverlayView(this);
        cameraFrame.addView(roiOverlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(cameraFrame, new LinearLayout.LayoutParams(-1, 0, 0.72f));

        mapView = new CornerMapView(this);
        root.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1.20f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(8, 5, 8, 5);

        Button cal = makeButton("CALIBRATE");
        cal.setOnClickListener(v -> {
            processor.startCalibration();
            mapView.clearHistory();
            setStatus("Calibrating. Keep phone and hidden area still.", 0xFFFFCC55);
        });
        controls.addView(cal, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button mode = makeButton("SINGLE EDGE");
        mode.setOnClickListener(v -> {
            dualMode = !dualMode;
            mode.setText(dualMode ? "DUAL EDGE" : "SINGLE EDGE");
            roiOverlay.setDual(dualMode);
            mapView.setDualMode(dualMode);
            processor.startCalibration();
            mapView.clearHistory();
            setStatus(dualMode ? "Dual-edge relative 2D mode. Recalibrate with both edge patches in the box." : "Single-edge angle mode. Recalibrating.", 0xFF8DEEFF);
        });
        controls.addView(mode, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView gainText = new TextView(this);
        gainText.setText("SENS");
        gainText.setTextColor(Color.WHITE);
        gainText.setGravity(Gravity.CENTER);
        controls.addView(gainText, new LinearLayout.LayoutParams(dp(50), dp(52)));

        SeekBar gain = new SeekBar(this);
        gain.setMax(100);
        gain.setProgress(32);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                processor.setGain(0.5f + 3.5f * progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        controls.addView(gain, new LinearLayout.LayoutParams(0, dp(52), 0.75f));
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(62)));

        status = new TextView(this);
        status.setText("Camera starting…");
        status.setTextColor(0xFF8DEEFF);
        status.setTextSize(13f);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(38)));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        startCameraThread();
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (texture.isAvailable() && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        sensorManager.unregisterListener(this);
        stopCameraThread();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable()) openCamera();
        } else if (requestCode == REQ_CAMERA) {
            setStatus("Camera permission is required.", 0xFFFF6666);
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float mag = (float)Math.sqrt(x*x + y*y + z*z);
            if (mag > 0.075f) unstableUntilNs = System.nanoTime() + 280_000_000L;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("CornerSightCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try { cameraThread.join(); } catch (InterruptedException ignored) {}
        cameraThread = null;
        cameraHandler = null;
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera() {
        if (camera != null || cameraHandler == null) return;
        try {
            CameraManager cm = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null) sensorOrientation = so;
                    break;
                }
            }
            if (cameraId == null && cm.getCameraIdList().length > 0) cameraId = cm.getCameraIdList()[0];
            if (cameraId == null) {
                setStatus("No camera found.", 0xFFFF6666);
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(cameraId, cameraState, cameraHandler);
        } catch (Exception e) {
            setStatus("Camera error: " + e.getMessage(), 0xFFFF6666);
        }
    }

    private final CameraDevice.StateCallback cameraState = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice c) {
            camera = c;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice c) {
            c.close();
            camera = null;
        }
        @Override public void onError(CameraDevice c, int error) {
            c.close();
            camera = null;
            setStatus("Camera failure " + error, 0xFFFF6666);
        }
    };

    private void createSession() {
        try {
            if (camera == null || !texture.isAvailable()) return;
            reader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, cameraHandler);

            SurfaceTexture st = texture.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(st);
            Surface analysisSurface = reader.getSurface();

            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(previewSurface);
            req.addTarget(analysisSurface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(Arrays.asList(previewSurface, analysisSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, cameraHandler);
                        setStatus("Ready. Put cyan box on the corner light patch, then CALIBRATE.", 0xFF8DEEFF);
                    } catch (CameraAccessException ex) {
                        setStatus("Capture error: " + ex.getMessage(), 0xFFFF6666);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("Camera stream setup failed.", 0xFFFF6666);
                }
            }, cameraHandler);
        } catch (Exception e) {
            setStatus("Camera setup error: " + e.getMessage(), 0xFFFF6666);
        }
    }

    private void onImage(ImageReader ir) {
        Image image = ir.acquireLatestImage();
        if (image == null) return;
        try {
            Image.Plane yp = image.getPlanes()[0];
            ByteBuffer buf = yp.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yp.getRowStride();
            int pixelStride = yp.getPixelStride();

            byte[] packed = new byte[width * height];
            if (pixelStride == 1 && rowStride == width && buf.remaining() >= packed.length) {
                buf.get(packed, 0, packed.length);
            } else {
                for (int yy = 0; yy < height; yy++) {
                    int base = yy * rowStride;
                    for (int xx = 0; xx < width; xx++) {
                        int idx = base + xx * pixelStride;
                        if (idx >= 0 && idx < buf.limit()) packed[yy * width + xx] = buf.get(idx);
                    }
                }
            }

            RectF roi = roiOverlay.getNormalizedRoi();
            RectF sensorRoi = displayToSensor(roi);
            long now = System.nanoTime();
            boolean stable = now > unstableUntilNs;
            PenumbraProcessor.Result r = processor.process(
                    packed, width, height, width, sensorRoi, stable, dualMode, now);
            mapView.push(r);

            long ms = SystemClock.elapsedRealtime();
            if (ms - lastStatusMs > 350) {
                lastStatusMs = ms;
                if (r.calibrating) {
                    int pct = Math.min(100, r.calibrationFrame * 100 / PenumbraProcessor.CAL_FRAMES);
                    setStatus("Learning empty corner… " + pct + "%", 0xFFFFCC55);
                } else if (r.frozen) {
                    setStatus("Hold phone still. Motion rejected.", 0xFFFF9955);
                } else if (r.confidence > 0.45f) {
                    String dir = Math.abs(r.angularSpeed) < 4f ? "slow/still" : r.angularSpeed > 0f ? "right" : "left";
                    setStatus(String.format(Locale.US, "Hidden target %s • %.0f%% confidence", dir, r.confidence * 100f), 0xFFFF6688);
                } else {
                    setStatus("Watching hidden side through indirect light.", 0xFF8DEEFF);
                }
            }
        } finally {
            image.close();
        }
    }

    private RectF displayToSensor(RectF d) {
        if (sensorOrientation == 90) {
            return clampRect(new RectF(d.top, 1f - d.right, d.bottom, 1f - d.left));
        }
        if (sensorOrientation == 270) {
            return clampRect(new RectF(1f - d.bottom, d.left, 1f - d.top, d.right));
        }
        if (sensorOrientation == 180) {
            return clampRect(new RectF(1f - d.right, 1f - d.bottom, 1f - d.left, 1f - d.top));
        }
        return clampRect(new RectF(d));
    }

    private static RectF clampRect(RectF r) {
        float l = clamp(r.left, 0f, 0.98f);
        float t = clamp(r.top, 0f, 0.98f);
        float rr = clamp(r.right, l + 0.01f, 1f);
        float b = clamp(r.bottom, t + 0.01f, 1f);
        return new RectF(l, t, rr, b);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        session = null;
        reader = null;
        camera = null;
    }

    private void setStatus(String s, int color) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setTextColor(color);
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
