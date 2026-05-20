package com.plasmacam;

import android.Manifest;
import java.util.ArrayList;
import android.os.Build;
import android.net.wifi.WifiManager;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;
import android.hardware.Sensor;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final String TAG = "PlasmaEye";
    private static final int REQUEST_CAMERA = 1;

    private TextureView textureView;
    private OverlayView overlayView;

    private SensorFieldView sensorFieldView;
    private SensorManager sensorManager;
    private Sensor magnetometer;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor lightSensor;
    private WifiManager wifiManager;
    private BroadcastReceiver wifiReceiver;
    private Handler sensorUiHandler;
    private Runnable wifiScanRunnable;
    private Button modeBtn;
    private TextView modeLabel, tvSensitivity, tvAmplify;
    private SeekBar seekSensitivity, seekAmplify;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder repeatingBuilder;
    private ImageReader imageReader;

    private HandlerThread bgThread;
    private Handler bgHandler;

    private Range<Integer> isoRange;
    private Range<Long> exposureRange;
    private boolean manualSensorAvailable = false;

    private volatile int isoSlider = 70;
    private volatile int exposureSlider = 45;
    private volatile int currentMode = PlasmaEngine.MODE_LIVE;

    private static final String[] MODE_NAMES = {
            "Manual Sensor",
            "Weak-Light Stack",
            "Frame Delta",
            "Red / IR Bias"
    };

    private final TextureView.SurfaceTextureListener textureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        overlayView = findViewById(R.id.overlayView);
        sensorFieldView = findViewById(R.id.sensorFieldView);
        modeBtn = findViewById(R.id.btnMode);
        modeLabel = findViewById(R.id.tvMode);
        seekSensitivity = findViewById(R.id.seekSensitivity);
        seekAmplify = findViewById(R.id.seekAmplify);
        tvSensitivity = findViewById(R.id.tvSensitivity);
        tvAmplify = findViewById(R.id.tvAmplify);

        seekSensitivity.setProgress(isoSlider);
        seekAmplify.setProgress(exposureSlider);

        modeBtn.setOnClickListener(v -> {
            currentMode = (currentMode + 1) % MODE_NAMES.length;
            updateModeText();
        setupSensorFieldSystem();
        });

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                isoSlider = p;
                updateModeText();
                updateRepeatingRequest();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        seekAmplify.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                exposureSlider = p;
                updateModeText();
                updateRepeatingRequest();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        updateModeText();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            startCameraWhenReady();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQUEST_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        }
    }

    private void startCameraWhenReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;

        startBackgroundThread();

        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(textureListener);
    }

    private void openCamera() {
        if (cameraDevice != null) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String camId = chooseBackCamera(manager);
            CameraCharacteristics cc = manager.getCameraCharacteristics(camId);

            isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);

            int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            manualSensorAvailable = false;
            if (caps != null) {
                for (int c : caps) {
                    if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                        manualSensorAvailable = true;
                        break;
                    }
                }
            }

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            Size chosen = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));

            imageReader = ImageReader.newInstance(
                    chosen.getWidth(),
                    chosen.getHeight(),
                    ImageFormat.YUV_420_888,
                    3
            );

            imageReader.setOnImageAvailableListener(this::processFrame, bgHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return;

            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cam) {
                    cameraDevice = cam;
                    startCapture();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cam) {
                    cam.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cam, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    cam.close();
                    cameraDevice = null;
                }
            }, bgHandler);

        } catch (Exception e) {
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private String chooseBackCamera(CameraManager manager) throws CameraAccessException {
        String fallback = manager.getCameraIdList()[0];

        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }

        return fallback;
    }

    private void startCapture() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || imageReader == null || cameraDevice == null) return;

            st.setDefaultBufferSize(imageReader.getWidth(), imageReader.getHeight());

            Surface previewSurface = new Surface(st);
            Surface readerSurface = imageReader.getSurface();

            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeatingBuilder.addTarget(previewSurface);
            repeatingBuilder.addTarget(readerSurface);

            applySensorControls(repeatingBuilder);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, readerSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            updateRepeatingRequest();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                        }
                    },
                    bgHandler
            );

        } catch (Exception e) {
            Log.e(TAG, "Start capture failed", e);
        }
    }

    private void applySensorControls(CaptureRequest.Builder b) {
        if (b == null) return;

        try {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            if (manualSensorAvailable) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso());
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs());
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Manual sensor control rejected", e);
        }
    }

    private void updateRepeatingRequest() {
        if (captureSession == null || repeatingBuilder == null || bgHandler == null) return;

        try {
            applySensorControls(repeatingBuilder);
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Repeating request failed", e);
        }
    }

    private void processFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        try {
            int w = image.getWidth();
            int h = image.getHeight();

            int[] argb = yuvToArgb(image, w, h);
            int[] processed = PlasmaEngine.apply(argb, w, h, currentMode);

            if (overlayView.getHeight() > overlayView.getWidth() && w > h) {
                processed = rotate90Clockwise(processed, w, h);
                int oldW = w;
                w = h;
                h = oldW;
            }

            overlayView.setFrame(processed, w, h);

        } catch (Exception e) {
            Log.e(TAG, "Frame processing failed", e);
        } finally {
            image.close();
        }
    }

    private int[] yuvToArgb(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yStride = planes[0].getRowStride();
        int uvStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] argb = new int[w * h];

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int yIndex = row * yStride + col;
                int uvIndex = (row / 2) * uvStride + (col / 2) * uvPixelStride;

                int Y = yBuf.get(yIndex) & 0xFF;
                int U = uBuf.get(uvIndex) & 0xFF;
                int V = vBuf.get(uvIndex) & 0xFF;

                int r = clamp((int)(Y + 1.370705f * (V - 128)));
                int g = clamp((int)(Y - 0.698001f * (V - 128) - 0.337633f * (U - 128)));
                int b = clamp((int)(Y + 1.732446f * (U - 128)));

                argb[row * w + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        return argb;
    }

    private int[] rotate90Clockwise(int[] src, int w, int h) {
        int[] out = new int[w * h];
        int newW = h;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int newX = h - 1 - y;
                int newY = x;
                out[newY * newW + newX] = src[y * w + x];
            }
        }

        return out;
    }

    private int currentIso() {
        if (isoRange == null) return 800;

        int min = Math.max(isoRange.getLower(), 50);
        int max = Math.max(min, isoRange.getUpper());

        double t = isoSlider / 100.0;
        int iso = (int)(min * Math.pow((double) max / min, t));

        return clamp(iso, min, max);
    }

    private long currentExposureNs() {
        long min = 1_000_000L;
        long max = 100_000_000L;

        if (exposureRange != null) {
            min = Math.max(exposureRange.getLower(), 100_000L);
            max = Math.min(exposureRange.getUpper(), 100_000_000L);
            if (max < min) max = min;
        }

        double t = exposureSlider / 100.0;
        long exp = (long)(min * Math.pow((double) max / min, t));

        return clamp(exp, min, max);
    }

    private void updateModeText() {
        String manual = manualSensorAvailable ? "MANUAL" : "AUTO-FALLBACK";

        if (modeLabel != null) {
            modeLabel.setText(MODE_NAMES[currentMode] + "  " + manual);
        }

        if (tvSensitivity != null) {
            tvSensitivity.setText(String.valueOf(currentIso()));
        }

        if (tvAmplify != null) {
            tvAmplify.setText(String.format(Locale.US, "%.1fms", currentExposureNs() / 1_000_000.0));
        }
    }

    private Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);

        Size best = sizes[0];

        for (Size s : sizes) {
            int w = s.getWidth();
            int h = s.getHeight();

            if (w <= 1280 && h <= 720) {
                best = s;
            }

            if (w == 640 && h == 480) {
                return s;
            }
        }

        return best;
    }

    private void startBackgroundThread() {
        if (bgThread != null) return;

        bgThread = new HandlerThread("SensorCapture");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        repeatingBuilder = null;
        stopSensorListeners();
        stopWifiLoop();
        stopBackgroundThread();
    }

    @Override
    protected void onResume() {
        super.onResume();

        startSensorListeners();
        startWifiLoop();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        }
    }
}
