package com.plasmacam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PlasmaEye";
    private static final int REQUEST_CAMERA = 1;

    private TextureView textureView;
    private Button modeBtn;
    private TextView modeLabel, tvSensitivity, tvAmplify;
    private SeekBar seekSensitivity, seekAmplify;
    private OverlayView overlayView;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler bgHandler;
    private HandlerThread bgThread;

    private int currentMode = PlasmaEngine.MODE_KIRLIAN;
    private volatile int sensitivity = 50;
    private volatile int amplify = 50;

    private static final String[] MODE_NAMES = {
        "Kirlian Corona", "Plasma Field", "Biophoton Dark-Field",
        "Aura Scan", "Gariaev Speckle", "Edge Differential", "Frequency Decomp"
    };

    private final TextureView.SurfaceTextureListener textureListener =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView     = findViewById(R.id.textureView);
        modeBtn         = findViewById(R.id.btnMode);
        modeLabel       = findViewById(R.id.tvMode);
        overlayView     = findViewById(R.id.overlayView);
        seekSensitivity = findViewById(R.id.seekSensitivity);
        seekAmplify     = findViewById(R.id.seekAmplify);
        tvSensitivity   = findViewById(R.id.tvSensitivity);
        tvAmplify       = findViewById(R.id.tvAmplify);

        modeLabel.setText(MODE_NAMES[currentMode]);
        modeBtn.setOnClickListener(v -> {
            currentMode = (currentMode + 1) % 7;
            modeLabel.setText(MODE_NAMES[currentMode]);
        });

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { sensitivity = p; tvSensitivity.setText(String.valueOf(p)); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        seekAmplify.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { amplify = p; tvAmplify.setText(String.valueOf(p)); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQUEST_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            textureView.setSurfaceTextureListener(textureListener);
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String camId = manager.getCameraIdList()[0];
            CameraCharacteristics cc = manager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size chosen = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));

            bgThread = new HandlerThread("PlasmaCapture");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());

            imageReader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::processFrame, bgHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice cam) { cameraDevice = cam; startCapture(); }
                @Override public void onDisconnected(@NonNull CameraDevice cam) { cam.close(); }
                @Override public void onError(@NonNull CameraDevice cam, int e) { cam.close(); }
            }, bgHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "Camera open failed", e); }
    }

    private void startCapture() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(1, 1);
            Surface dummy = new Surface(st);
            Surface reader = imageReader.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            cameraDevice.createCaptureSession(Arrays.asList(reader, dummy),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                        captureSession = s;
                        try { s.setRepeatingRequest(builder.build(), null, bgHandler); }
                        catch (CameraAccessException e) { Log.e(TAG, "Capture failed", e); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {}
                }, bgHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "Session failed", e); }
    }

    private void processFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            int w = image.getWidth(), h = image.getHeight();
            int[] argb = yuvToArgb(image, w, h);
            int[] filtered = PlasmaEngine.apply(argb, w, h, currentMode, sensitivity, amplify);
            overlayView.setFrame(filtered, w, h);
        } finally { image.close(); }
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
                int Y = yBuf.get(row * yStride + col) & 0xFF;
                int uvIdx = (row / 2) * uvStride + (col / 2) * uvPixelStride;
                int U = uBuf.get(uvIdx) & 0xFF;
                int V = vBuf.get(uvIdx) & 0xFF;
                int r = clamp((int)(Y + 1.370705f * (V - 128)));
                int g = clamp((int)(Y - 0.698001f * (V - 128) - 0.337633f * (U - 128)));
                int b = clamp((int)(Y + 1.732446f * (U - 128)));
                argb[row * w + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return argb;
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private Size chooseSize(Size[] sizes) {
        for (Size s : sizes) if (s.getWidth() <= 640 && s.getHeight() <= 480) return s;
        return sizes[sizes.length - 1];
    }

    @Override protected void onPause() {
        super.onPause();
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice  != null) { cameraDevice.close();  cameraDevice  = null; }
        if (bgThread      != null) { bgThread.quitSafely(); bgThread      = null; }
    }

    @Override protected void onResume() {
        super.onResume();
        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(textureListener);
    }
}
