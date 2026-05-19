package com.plasmacam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.view.View;
import android.widget.Button;
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
    private TextView modeLabel;
    private OverlayView overlayView;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler bgHandler;
    private HandlerThread bgThread;

    private int currentMode = PlasmaEngine.MODE_KIRLIAN;
    private static final String[] MODE_NAMES = {
        "Kirlian Corona", "Plasma Field", "Biophoton Dark-Field",
        "Aura Scan", "Gariaev Speckle", "UV Fluorescence", "IR Thermal"
    };

    private volatile int[] filteredPixels = null;
    private volatile int frameW = 0, frameH = 0;

    private final TextureView.SurfaceTextureListener textureListener =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
                openCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        modeBtn     = findViewById(R.id.btnMode);
        modeLabel   = findViewById(R.id.tvMode);
        overlayView = findViewById(R.id.overlayView);

        modeLabel.setText(MODE_NAMES[currentMode]);

        modeBtn.setOnClickListener(v -> {
            currentMode = (currentMode + 1) % 7;
            modeLabel.setText(MODE_NAMES[currentMode]);
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
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
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size chosen = chooseSize(sizes, 640, 480);

            bgThread = new HandlerThread("PlasmaCapture");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());

            imageReader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(),
                ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::processFrame, bgHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return;

            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice cam) {
                    cameraDevice = cam;
                    startCapture();
                }
                @Override public void onDisconnected(@NonNull CameraDevice cam) { cam.close(); }
                @Override public void onError(@NonNull CameraDevice cam, int e) { cam.close(); }
            }, bgHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private void startCapture() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(1, 1);
            Surface dummySurface = new Surface(st);
            Surface readerSurface = imageReader.getSurface();

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(readerSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            cameraDevice.createCaptureSession(Arrays.asList(readerSurface, dummySurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, bgHandler);
                        } catch (CameraAccessException e) { Log.e(TAG, "Capture failed", e); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                }, bgHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session failed", e);
        }
    }

    private void processFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = yuvToArgb(image, w, h);
            int[] filtered = PlasmaEngine.apply(argb, w, h, currentMode);
            filteredPixels = filtered;
            frameW = w;
            frameH = h;
            overlayView.postInvalidate();
        } finally {
            image.close();
        }
    }

    private int[] yuvToArgb(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf  = planes[0].getBuffer();
        ByteBuffer uBuf  = planes[1].getBuffer();
        ByteBuffer vBuf  = planes[2].getBuffer();
        int yStride = planes[0].getRowStride();
        int uvStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        int[] argb = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int yIdx = row * yStride + col;
                int uvRow = row / 2;
                int uvCol = col / 2;
                int uvIdx = uvRow * uvStride + uvCol * uvPixelStride;
                int Y = yBuf.get(yIdx) & 0xFF;
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

    private Size chooseSize(Size[] sizes, int prefW, int prefH) {
        for (Size s : sizes) {
            if (s.getWidth() <= 640 && s.getHeight() <= 480) return s;
        }
        return sizes[sizes.length - 1];
    }

    public class OverlayView extends android.view.View {
        public OverlayView(Context ctx, android.util.AttributeSet attrs) {
            super(ctx, attrs);
        }
        @Override protected void onDraw(Canvas canvas) {
            int[] px = filteredPixels;
            int w = frameW, h = frameH;
            if (px == null || w == 0 || h == 0) return;
            Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(bmp, null,
                new android.graphics.RectF(0, 0, getWidth(), getHeight()), null);
            bmp.recycle();
        }
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
