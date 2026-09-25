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
import android.graphics.RenderEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float effectiveZoom = 1f, nativeZoom = 1f, nativeMax = 1f, deepFactor = 1f, recordingDeepFactor = 1f;
    private int evIndex = 0;
    private boolean detailMode = true, steadyMode = false, teleLock = false, collapsed = false, cameraReady = false;
    private File rawRecording;

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> startCamera());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(R.layout.activity_main);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews(); setupControls(); requestPermissionsOrStart();
    }

    private void bindViews() {
        preview = findViewById(R.id.preview); status = findViewById(R.id.status); evLabel = findViewById(R.id.evLabel);
        advancedRow = findViewById(R.id.advancedRow); exposureRow = findViewById(R.id.exposureRow); presetRow = findViewById(R.id.presetRow);
        zoomSeek = findViewById(R.id.zoomSeek); recBtn = findViewById(R.id.recBtn); collapseBtn = findViewById(R.id.collapseBtn);
        detailBtn = findViewById(R.id.detailBtn); steadyBtn = findViewById(R.id.steadyBtn); teleBtn = findViewById(R.id.teleBtn);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE); preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
    }

    private void setupControls() {
        float[] presets = {1,5,10,25,50,100,250,500,1000};
        for (float p : presets) {
            Button b = new Button(this); b.setText(((int)p)+"×"); b.setTextSize(11f); b.setTextColor(0xFFFFFFFF); b.setBackgroundResource(R.drawable.btn_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(42)); lp.setMargins(dp(2),dp(2),dp(2),dp(2)); b.setLayoutParams(lp);
            b.setOnClickListener(v -> setEffectiveZoom(p)); presetRow.addView(b);
        }
        zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser && recording == null) setEffectiveZoom((float)Math.exp((progress/10000.0)*Math.log(1000.0))); }
            @Override public void onStartTrackingTouch(SeekBar s) {} @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { if (recording == null) setEffectiveZoom(effectiveZoom*d.getScaleFactor()); return true; }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { focusAt(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { setEffectiveZoom(effectiveZoom < 10f ? 10f : 1f); return true; }
        });
        preview.setOnTouchListener((v,e) -> { scaleDetector.onTouchEvent(e); gestureDetector.onTouchEvent(e); return true; });
        findViewById(R.id.photoBtn).setOnClickListener(v -> takePhoto()); recBtn.setOnClickListener(v -> toggleRecording()); collapseBtn.setOnClickListener(v -> toggleCollapse());
        detailBtn.setOnClickListener(v -> { detailMode=true; steadyMode=false; rebuildCamera(); });
        steadyBtn.setOnClickListener(v -> { steadyMode=true; detailMode=false; rebuildCamera(); });
        teleBtn.setOnClickListener(v -> { teleLock=!teleLock; teleBtn.setText(teleLock?"TELE LOCK ✓":"TELE LOCK"); if (teleLock && effectiveZoom<5f) setEffectiveZoom(5f); updateStatus("Tele lock "+(teleLock?"armed":"released")); });
        findViewById(R.id.evMinus).setOnClickListener(v -> adjustExposure(-1)); findViewById(R.id.evPlus).setOnClickListener(v -> adjustExposure(1));
    }

    private void requestPermissionsOrStart() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT<=28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) startCamera(); else permissions.launch(need.toArray(new String[0]));
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { updateStatus("Camera permission required"); return; }
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> { try { cameraProvider=f.get(); bindCameraUseCases(); } catch(Exception e) { updateStatus("Camera start error: "+e.getMessage()); } }, ContextCompat.getMainExecutor(this));
    }

    private void rebuildCamera() { if (recording==null && cameraProvider!=null) bindCameraUseCases(); }

    private void bindCameraUseCases() {
        try {
            cameraProvider.unbindAll();
            Preview.Builder pb = new Preview.Builder(); if (steadyMode) pb.setPreviewStabilizationEnabled(true); Preview p=pb.build(); p.setSurfaceProvider(preview.getSurfaceProvider());
            imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
            QualitySelector qs = QualitySelector.from(detailMode?Quality.UHD:Quality.FHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD));
            Recorder recorder = new Recorder.Builder().setQualitySelector(qs).build(); VideoCapture.Builder<Recorder> vb = new VideoCapture.Builder<>(recorder); if (steadyMode) vb.setVideoStabilizationEnabled(true); videoCapture=vb.build();
            CameraSelector selector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
            camera = cameraProvider.bindToLifecycle(this, selector, p, imageCapture, videoCapture); cameraReady=true;
            LiveData<ZoomState> zs = camera.getCameraInfo().getZoomState(); zs.observe(this, state -> { if (state!=null) { nativeMax=Math.max(1f,state.getMaxZoomRatio()); applyZoom(); } });
            applyDetailLook(); applyZoom(); updateStatus("Camera ready");
        } catch(Exception first) {
            if (steadyMode) { steadyMode=false; detailMode=false; Toast.makeText(this,"Preview stabilization unavailable; using standard mode",Toast.LENGTH_SHORT).show(); bindCameraUseCases(); }
            else updateStatus("Bind error: "+first.getClass().getSimpleName()+": "+first.getMessage());
        }
    }

    private void setEffectiveZoom(float z) { float min=teleLock?5f:1f; effectiveZoom=Math.max(min,Math.min(1000f,z)); zoomSeek.setProgress((int)Math.round(Math.log(effectiveZoom)/Math.log(1000.0)*10000)); applyZoom(); }
    private void applyZoom() {
        if (camera==null) return; nativeZoom=Math.max(1f,Math.min(effectiveZoom,nativeMax)); deepFactor=Math.max(1f,effectiveZoom/nativeZoom); camera.getCameraControl().setZoomRatio(nativeZoom);
        preview.setPivotX(preview.getWidth()/2f); preview.setPivotY(preview.getHeight()/2f); preview.setScaleX(deepFactor); preview.setScaleY(deepFactor); updateStatus(null);
    }

    private void focusAt(float x,float y) {
        if (camera==null) return; MeteringPointFactory f=preview.getMeteringPointFactory(); MeteringPoint p=f.createPoint(x,y);
        FocusMeteringAction a=new FocusMeteringAction.Builder(p,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(4,TimeUnit.SECONDS).build(); camera.getCameraControl().startFocusAndMetering(a); updateStatus("AF/AE target locked");
    }

    private void adjustExposure(int delta) {
        if (camera==null) return; int min=camera.getCameraInfo().getExposureState().getExposureCompensationRange().getLower(); int max=camera.getCameraInfo().getExposureState().getExposureCompensationRange().getUpper();
        evIndex=Math.max(min,Math.min(max,evIndex+delta)); camera.getCameraControl().setExposureCompensationIndex(evIndex); evLabel.setText("EV "+(evIndex>0?"+":"")+evIndex); updateStatus("Exposure adjusted");
    }

    private void takePhoto() {
        if (!cameraReady || imageCapture==null) return; File raw=new File(getCacheDir(),"maxsight_raw_"+System.currentTimeMillis()+".jpg"); ImageCapture.OutputFileOptions opts=new ImageCapture.OutputFileOptions.Builder(raw).build(); final float factor=deepFactor;
        imageCapture.takePicture(opts,cameraExecutor,new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) { try { Bitmap src=loadUpright(raw); if(src==null) throw new IOException("decode failed"); Bitmap out=deepCropAndEnhance(src,factor); saveBitmapToGallery(out); if(out!=src) out.recycle(); src.recycle(); raw.delete(); runOnUiThread(() -> toast("PHOTO SAVED • "+formatZoom(effectiveZoom))); } catch(Exception e) { runOnUiThread(() -> toast("Photo error: "+e.getMessage())); } }
            @Override public void onError(@NonNull ImageCaptureException exception) { runOnUiThread(() -> toast("Photo capture error: "+exception.getMessage())); }
        });
    }

    private Bitmap loadUpright(File file) throws IOException {
        Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath()); if(b==null) return null; ExifInterface exif=new ExifInterface(file); int o=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL); int degrees=0;
        if(o==ExifInterface.ORIENTATION_ROTATE_90) degrees=90; else if(o==ExifInterface.ORIENTATION_ROTATE_180) degrees=180; else if(o==ExifInterface.ORIENTATION_ROTATE_270) degrees=270; if(degrees==0) return b;
        Matrix m=new Matrix(); m.postRotate(degrees); Bitmap r=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true); if(r!=b) b.recycle(); return r;
    }

    private Bitmap deepCropAndEnhance(Bitmap src,float factor) {
        if(factor<=1.001f) return src; int sw=src.getWidth(), sh=src.getHeight(); int cw=Math.max(2,Math.round(sw/factor)), ch=Math.max(2,Math.round(sh/factor)); int left=Math.max(0,(sw-cw)/2), top=Math.max(0,(sh-ch)/2);
        Bitmap crop=Bitmap.createBitmap(src,left,top,Math.min(cw,sw-left),Math.min(ch,sh-top)); Bitmap scaled=Bitmap.createScaledBitmap(crop,sw,sh,true); if(crop!=src) crop.recycle(); if(!detailMode) return scaled;
        Bitmap boosted=Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888); Canvas c=new Canvas(boosted); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG); ColorMatrix cm=new ColorMatrix(); cm.setSaturation(1.08f);
        ColorMatrix contrast=new ColorMatrix(new float[]{1.12f,0,0,0,-15, 0,1.12f,0,0,-15, 0,0,1.12f,0,-15, 0,0,0,1,0}); cm.postConcat(contrast); paint.setColorFilter(new ColorMatrixColorFilter(cm)); c.drawBitmap(scaled,0,0,paint); scaled.recycle(); return boosted;
    }

    private void toggleRecording() {
        if(!cameraReady || videoCapture==null) return; if(recording!=null){ recording.stop(); return; }
        rawRecording=new File(getCacheDir(),"maxsight_raw_"+System.currentTimeMillis()+".mp4"); recordingDeepFactor=deepFactor; FileOutputOptions opts=new FileOutputOptions.Builder(rawRecording).build(); PendingRecording pending=videoCapture.getOutput().prepareRecording(this,opts);
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) pending=pending.withAudioEnabled();
        recording=pending.start(ContextCompat.getMainExecutor(this),event -> {
            if(event instanceof VideoRecordEvent.Start){ recBtn.setText("STOP"); recBtn.setTextColor(0xFFFFFFFF); updateStatus("REC LOCK • deep crop fixed at "+formatZoom(effectiveZoom)); }
            else if(event instanceof VideoRecordEvent.Finalize){ VideoRecordEvent.Finalize f=(VideoRecordEvent.Finalize)event; recording=null; recBtn.setText("REC"); recBtn.setTextColor(0xFFFF4A5C); if(f.hasError()){ updateStatus("Recording error "+f.getError()); if(rawRecording!=null) rawRecording.delete(); } else exportDeepVideo(rawRecording,recordingDeepFactor); }
        });
    }

    private void exportDeepVideo(File input,float factor) {
        updateStatus("DEEP EXPORT • processing saved video…"); File out=new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),"MaxSight_x1000_"+System.currentTimeMillis()+".mp4"); if(out.getParentFile()!=null) out.getParentFile().mkdirs(); List<Effect> videoEffects=new ArrayList<>();
        if(factor>1.001f){ float edge=Math.max(0.001f,Math.min(1f,1f/factor)); videoEffects.add(new Crop(-edge,edge,-edge,edge)); videoEffects.add(Presentation.createForHeight(detailMode?2160:1080)); }
        Effects effects=new Effects(Collections.emptyList(),videoEffects); EditedMediaItem item=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(effects).build();
        Transformer transformer=new Transformer.Builder(this).addListener(new Transformer.Listener(){
            @Override public void onCompleted(@NonNull Composition composition,@NonNull ExportResult result){ try{ copyVideoToGallery(out); input.delete(); out.delete(); updateStatus("VIDEO SAVED • deep export complete"); toast("VIDEO SAVED"); }catch(Exception e){ updateStatus("Gallery copy error: "+e.getMessage()); } }
            @Override public void onError(@NonNull Composition composition,@NonNull ExportResult result,@NonNull ExportException exception){ updateStatus("Deep export error: "+exception.getMessage()); try{ copyVideoToGallery(input); toast("RAW VIDEO SAVED"); }catch(Exception ignored){} }
        }).build(); transformer.start(item,out.getAbsolutePath());
    }

    private Uri saveBitmapToGallery(Bitmap bitmap) throws IOException {
        String name="MaxSight_x1000_"+System.currentTimeMillis()+".jpg"; ContentResolver cr=getContentResolver(); ContentValues v=new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME,name); v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg"); if(Build.VERSION.SDK_INT>=29) v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/MaxSightX1000"); Uri uri=cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v); if(uri==null) throw new IOException("MediaStore insert failed"); try(OutputStream os=cr.openOutputStream(uri)){ if(os==null || !bitmap.compress(Bitmap.CompressFormat.JPEG,95,os)) throw new IOException("JPEG write failed"); } return uri;
    }
    private Uri copyVideoToGallery(File file) throws IOException {
        ContentResolver cr=getContentResolver(); ContentValues v=new ContentValues(); v.put(MediaStore.Video.Media.DISPLAY_NAME,"MaxSight_x1000_"+System.currentTimeMillis()+".mp4"); v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); if(Build.VERSION.SDK_INT>=29) v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/MaxSightX1000"); Uri uri=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v); if(uri==null) throw new IOException("MediaStore insert failed"); try(InputStream in=new FileInputStream(file); OutputStream os=cr.openOutputStream(uri)){ if(os==null) throw new IOException("video output failed"); byte[] buf=new byte[1024*1024]; int n; while((n=in.read(buf))>0) os.write(buf,0,n); } return uri;
    }

    private void applyDetailLook() {
        if(Build.VERSION.SDK_INT>=31){ if(detailMode){ ColorMatrix cm=new ColorMatrix(new float[]{1.08f,0,0,0,-8, 0,1.08f,0,0,-8, 0,0,1.08f,0,-8, 0,0,0,1,0}); preview.setRenderEffect(RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm))); } else preview.setRenderEffect(null); }
        detailBtn.setText(detailMode?"DETAIL UHD ✓":"DETAIL UHD"); steadyBtn.setText(steadyMode?"STEADY FHD ✓":"STEADY FHD");
    }
    private void toggleCollapse(){ collapsed=!collapsed; presetRow.setVisibility(collapsed?View.GONE:View.VISIBLE); zoomSeek.setVisibility(collapsed?View.GONE:View.VISIBLE); advancedRow.setVisibility(collapsed?View.GONE:View.VISIBLE); exposureRow.setVisibility(collapsed?View.GONE:View.VISIBLE); collapseBtn.setText(collapsed?"▲":"▼"); }
    private void updateStatus(String note){ runOnUiThread(() -> { String mode=detailMode?"DETAIL-UHD":(steadyMode?"STEADY-FHD":"STANDARD"); String base=String.format(Locale.US,"%s  |  %s  | native %.1f×/%.1f×  | deep %.1f×",formatZoom(effectiveZoom),mode,nativeZoom,nativeMax,deepFactor); status.setText(note==null?base:note+"\n"+base); }); }
    private String formatZoom(float z){ return z<10?String.format(Locale.US,"%.1f×",z):String.format(Locale.US,"%.0f×",z); }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private void toast(String s){ runOnUiThread(() -> Toast.makeText(this,s,Toast.LENGTH_SHORT).show()); }
    @Override protected void onDestroy(){ super.onDestroy(); if(recording!=null) recording.stop(); if(cameraExecutor!=null) cameraExecutor.shutdown(); }
}
