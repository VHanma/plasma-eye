package com.omega.synesthesia;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener, TextToSpeech.OnInitListener {
    private static final int REQ = 71;
    private final FusionEngine fusion = new FusionEngine();
    private final CrossSenseEngine cross = new CrossSenseEngine();
    private final CameraSense cameraSense = new CameraSense();

    private SensorManager sm;
    private Sensor accel, gyro, mag, light;
    private volatile float vibration, rotation, magnetic, lightDelta, lastLight;
    private volatile float audioEnergy, visualMotion, cameraLightChange, leftMotion, rightMotion;

    private AudioRecord audio;
    private Thread audioThread;
    private volatile boolean audioRunning;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession cameraSession;
    private ImageReader imageReader;
    private TextureView preview;

    private SynesthesiaView field;
    private TextView state, detail, crossReadout;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String lastSpoken = "";
    private long lastSpeak = 0;
    private int blindMode = 0; // 0 all, 1 vision hidden, 2 hearing hidden

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(5,7,11));
        getWindow().setNavigationBarColor(Color.rgb(5,7,11));
        buildUi();
        sm = (SensorManager)getSystemService(SENSOR_SERVICE);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        tts = new TextToSpeech(this, this);
        if (!permissionsReady()) requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
        else startPerception();
        new Handler().postDelayed(tick, 160);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22,16,22,18);
        root.setBackgroundColor(Color.rgb(5,7,11));

        TextView title = text("Ω SYNESTHESIA v0.2", 27, Color.WHITE);
        root.addView(title);
        root.addView(text("One field. Different forms of feeling. The senses now predict each other.", 14, 0xFF9EB2BA));

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(surfaceListener);
        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(150)));

        field = new SynesthesiaView(this);
        root.addView(field, new LinearLayout.LayoutParams(-1, 0, 1f));

        state = text("AWAKENING", 24, 0xFF67F7E7);
        state.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(state);

        detail = text("Learning how this environment feels.", 16, Color.WHITE);
        detail.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(detail);

        crossReadout = text("Cross-sense model warming up…", 15, 0xFFFFD37A);
        crossReadout.setPadding(0,10,0,10);
        crossReadout.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(crossReadout);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button blind = new Button(this);
        blind.setText("ALL SENSES");
        blind.setOnClickListener(v -> {
            blindMode = (blindMode + 1) % 3;
            blind.setText(blindMode == 0 ? "ALL SENSES" : blindMode == 1 ? "BLIND VISION" : "BLIND HEARING");
            lastSpoken = "";
        });
        controls.addView(blind, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button relearn = new Button(this);
        relearn.setText("RELEARN");
        relearn.setOnClickListener(v -> recreate());
        controls.addView(relearn, new LinearLayout.LayoutParams(0, dp(52), .72f));
        root.addView(controls);
        setContentView(root);
    }

    private TextView text(String s,int size,int color){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,5,0,5);return v;
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            ttsReady = tts.setLanguage(Locale.US) >= 0;
            if (ttsReady) { tts.setSpeechRate(.96f); tts.setPitch(.95f); }
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if (sm != null) for(Sensor s:new Sensor[]{accel,gyro,mag,light}) if(s!=null) sm.registerListener(this,s,SensorManager.SENSOR_DELAY_GAME);
        if (permissionsReady()) startPerception();
    }

    @Override protected void onPause(){
        closeCamera();
        if(sm!=null)sm.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy(){
        stopAudio(); stopCameraThread();
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e){
        float m=0;for(float x:e.values)m+=x*x;m=(float)Math.sqrt(m);
        if(e.sensor==accel)vibration=Math.abs(m-9.80665f);
        else if(e.sensor==gyro)rotation=m;
        else if(e.sensor==mag)magnetic=m;
        else if(e.sensor==light){if(lastLight>0)lightDelta=Math.abs(e.values[0]-lastLight)/Math.max(1,lastLight);lastLight=e.values[0];}
    }
    @Override public void onAccuracyChanged(Sensor s,int a){}

    private boolean permissionsReady(){
        return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }

    private void startPerception(){ startAudio(); startCameraThread(); if(preview!=null&&preview.isAvailable())openCamera(); }

    private void startAudio(){
        if(audioRunning)return;
        try{
            int rate=16000,min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            audio=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(4096,min));
            audio.startRecording();audioRunning=true;
            audioThread=new Thread(()->{short[] b=new short[1024];while(audioRunning){int n=audio.read(b,0,b.length);double q=0;for(int i=0;i<n;i++)q+=(double)b[i]*b[i];if(n>0)audioEnergy=(float)Math.sqrt(q/n)/32768f;}},"OmegaAudio");
            audioThread.start();
        }catch(Exception ignored){audioEnergy=0;}
    }

    private void stopAudio(){
        audioRunning=false;
        try{if(audio!=null){audio.stop();audio.release();}}catch(Exception ignored){}
        audio=null;
    }

    private void startCameraThread(){
        if(cameraThread!=null)return;
        cameraThread=new HandlerThread("OmegaVision");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread(){
        if(cameraThread==null)return;
        cameraThread.quitSafely();
        try{cameraThread.join();}catch(Exception ignored){}
        cameraThread=null;cameraHandler=null;
    }

    private final TextureView.SurfaceTextureListener surfaceListener=new TextureView.SurfaceTextureListener(){
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){if(permissionsReady()){startCameraThread();openCamera();}}
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){return true;}
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}
    };

    private void openCamera(){
        if(camera!=null||cameraHandler==null||!permissionsReady())return;
        try{
            CameraManager cm=(CameraManager)getSystemService(Context.CAMERA_SERVICE);String chosen=null;
            for(String id:cm.getCameraIdList()){Integer f=cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK){chosen=id;break;}}
            if(chosen==null&&cm.getCameraIdList().length>0)chosen=cm.getCameraIdList()[0];
            if(chosen!=null)cm.openCamera(chosen,cameraState,cameraHandler);
        }catch(Exception e){runOnUiThread(()->crossReadout.setText("Camera sense unavailable: "+e.getMessage()));}
    }

    private final CameraDevice.StateCallback cameraState=new CameraDevice.StateCallback(){
        @Override public void onOpened(CameraDevice c){camera=c;createCameraSession();}
        @Override public void onDisconnected(CameraDevice c){c.close();camera=null;}
        @Override public void onError(CameraDevice c,int e){c.close();camera=null;}
    };

    private void createCameraSession(){
        try{
            SurfaceTexture st=preview.getSurfaceTexture();if(st==null||camera==null)return;st.setDefaultBufferSize(640,480);
            Surface ps=new Surface(st);
            imageReader=ImageReader.newInstance(320,240,android.graphics.ImageFormat.YUV_420_888,2);
            imageReader.setOnImageAvailableListener(this::onImage,cameraHandler);
            CaptureRequest.Builder r=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);r.addTarget(ps);r.addTarget(imageReader.getSurface());
            r.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(ps,imageReader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession s){cameraSession=s;try{s.setRepeatingRequest(r.build(),null,cameraHandler);}catch(Exception ignored){}}
                @Override public void onConfigureFailed(CameraCaptureSession s){}
            },cameraHandler);
        }catch(Exception ignored){}
    }

    private void onImage(ImageReader ir){
        Image img=ir.acquireLatestImage();if(img==null)return;
        try{
            Image.Plane p=img.getPlanes()[0];ByteBuffer b=p.getBuffer();int w=img.getWidth(),h=img.getHeight(),rs=p.getRowStride(),ps=p.getPixelStride();byte[] y=new byte[w*h];
            for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++){int idx=yy*rs+xx*ps;if(idx<b.limit())y[yy*w+xx]=b.get(idx);}
            CameraSense.Reading q=cameraSense.process(y,w,h);visualMotion=q.motion;cameraLightChange=q.lightChange;leftMotion=q.leftMotion;rightMotion=q.rightMotion;
        }finally{img.close();}
    }

    private final Runnable tick=new Runnable(){@Override public void run(){
        float combinedLight=Math.max(lightDelta,cameraLightChange);
        CrossSenseEngine.Prediction cp=cross.update(visualMotion,audioEnergy,vibration,rotation,magnetic,combinedLight);
        float effectiveVisual=blindMode==1?cp.predictedVisual:visualMotion;
        float effectiveAudio=blindMode==2?cp.predictedAudio:audioEnergy;
        FusionEngine.Percept p=fusion.fuse(effectiveVisual,effectiveAudio,vibration,rotation,magnetic,combinedLight);
        state.setText(p.statement);detail.setText(p.detail);field.setPercept(p);

        String extra;
        if(blindMode==1){
            int score=Math.round(cp.visualAgreement*100);extra="BLIND VISION • other senses are estimating sight\nPrediction agreement: "+score+"%";
            if(score>70&&Math.abs(visualMotion-cp.predictedVisual)<.12f)extra+="\nThe hidden camera channel agrees with the synthetic visual sense.";
        }else if(blindMode==2){
            int score=Math.round(cp.audioAgreement*100);extra="BLIND HEARING • other senses are estimating sound\nPrediction agreement: "+score+"%";
            if(score>70&&Math.abs(audioEnergy-cp.predictedAudio)<.12f)extra+="\nThe hidden microphone channel agrees with the synthetic hearing sense.";
        }else{
            String side=Math.abs(leftMotion-rightMotion)<.03f?"balanced visual movement":leftMotion>rightMotion?"more visual movement on the left":"more visual movement on the right";
            extra="ALL SENSES FUSED • "+side+"\nVision↔others learning: "+Math.round(cp.visualAgreement*100)+"%   Hearing↔others: "+Math.round(cp.audioAgreement*100)+"%";
        }
        crossReadout.setText(extra);
        if((p.statement.contains("COHERENT")||p.statement.contains("UNKNOWN"))&&!p.statement.equals(lastSpoken)&&System.currentTimeMillis()-lastSpeak>4000){
            if(ttsReady)tts.speak(p.statement+". "+p.detail,TextToSpeech.QUEUE_FLUSH,null,"omega_event");lastSpoken=p.statement;lastSpeak=System.currentTimeMillis();
        }
        vibration*=.78f;rotation*=.82f;lightDelta*=.75f;cameraLightChange*=.82f;
        new Handler().postDelayed(this,160);
    }};

    private void closeCamera(){
        try{if(cameraSession!=null)cameraSession.close();}catch(Exception ignored){}
        try{if(imageReader!=null)imageReader.close();}catch(Exception ignored){}
        try{if(camera!=null)camera.close();}catch(Exception ignored){}
        cameraSession=null;imageReader=null;camera=null;
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ&&permissionsReady())startPerception();else crossReadout.setText("Camera + microphone permissions are needed for full synesthesia mode.");}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
