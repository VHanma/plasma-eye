package com.vanhanma.omegalens;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.hardware.camera2.*;
import android.media.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements SensorEventListener {
    private TextureView cameraView;
    private OmegaOverlay overlay;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread camThread;
    private Handler camHandler;
    private SensorManager sm;
    private Sensor accel, gyro, magnet, light;
    private final float[] a = new float[3], g = new float[3], m = new float[3];
    private float lux = 0, magnetic = 0, lastMagnetic = 0, magDelta = 0;
    private AudioRecord audio;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private final ExecutorService visionExec = Executors.newSingleThreadExecutor();
    private volatile boolean visionRunning;
    private final int REQ = 77;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        sm = (SensorManager)getSystemService(SENSOR_SERVICE);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (Build.VERSION.SDK_INT >= 23 && (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
        } else startEngines();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cameraView = new TextureView(this);
        root.addView(cameraView, new FrameLayout.LayoutParams(-1,-1));
        overlay = new OmegaOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL); top.setPadding(dp(14),dp(12),dp(14),0);
        TextView title = new TextView(this); title.setText("Ω  REALITY LENS"); title.setTextSize(22); title.setTextColor(Color.rgb(124,255,239)); title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this); sub.setText("MULTI-SENSOR TEMPORAL OBSERVATORY"); sub.setTextSize(10); sub.setTextColor(Color.argb(190,190,255,246));
        top.addView(title); top.addView(sub);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1,-2); tp.gravity = Gravity.TOP; root.addView(top,tp);

        LinearLayout modes = new LinearLayout(this); modes.setOrientation(LinearLayout.HORIZONTAL); modes.setGravity(Gravity.CENTER); modes.setPadding(dp(6),dp(6),dp(6),dp(14));
        String[] names={"ECHO","FIELD","SONIC","FUSION","Ω"}; int[] ids={0,1,2,3,4};
        for(int i=0;i<names.length;i++){ final int id=ids[i]; Button bt=new Button(this); bt.setText(names[i]); bt.setTextSize(10); bt.setTextColor(Color.WHITE); bt.setAllCaps(false); bt.setPadding(0,0,0,0); GradientDrawable gd=new GradientDrawable(); gd.setColor(Color.argb(100,0,0,0)); gd.setStroke(dp(1),Color.argb(180,124,255,239)); gd.setCornerRadius(dp(18)); bt.setBackground(gd); bt.setOnClickListener(v->{overlay.mode=id; overlay.flashLabel(names[id]);}); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(42),1); bp.setMargins(dp(3),0,dp(3),0); modes.addView(bt,bp); }
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1,-2); mp.gravity=Gravity.BOTTOM; root.addView(modes,mp);
        setContentView(root);
    }

    private int dp(int x){ return (int)(x*getResources().getDisplayMetrics().density+0.5f); }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] q){ super.onRequestPermissionsResult(r,p,q); if(r==REQ) startEngines(); }

    private void startEngines(){
        startSensors();
        cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s,int w,int h){ openCamera(); startVision(); }
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s){return true;}
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s){}
        });
        if(cameraView.isAvailable()){ openCamera(); startVision(); }
        startAudio();
    }

    private void startSensors(){
        if(accel!=null) sm.registerListener(this,accel,SensorManager.SENSOR_DELAY_GAME);
        if(gyro!=null) sm.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);
        if(magnet!=null) sm.registerListener(this,magnet,SensorManager.SENSOR_DELAY_GAME);
        if(light!=null) sm.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_ACCELEROMETER) System.arraycopy(e.values,0,a,0,3);
        else if(e.sensor.getType()==Sensor.TYPE_GYROSCOPE) System.arraycopy(e.values,0,g,0,3);
        else if(e.sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD){ System.arraycopy(e.values,0,m,0,3); magnetic=(float)Math.sqrt(m[0]*m[0]+m[1]*m[1]+m[2]*m[2]); magDelta=0.85f*magDelta+0.15f*Math.abs(magnetic-lastMagnetic); lastMagnetic=magnetic; }
        else if(e.sensor.getType()==Sensor.TYPE_LIGHT) lux=e.values[0];
        overlay.setSensors(a,g,m,magnetic,magDelta,lux);
    }
    @Override public void onAccuracyChanged(Sensor s,int x){}

    private void openCamera(){
        try{
            if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return;
            camThread=new HandlerThread("omega-camera"); camThread.start(); camHandler=new Handler(camThread.getLooper());
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE); String chosen=null;
            for(String id:cm.getCameraIdList()){ CameraCharacteristics cc=cm.getCameraCharacteristics(id); Integer f=cc.get(CameraCharacteristics.LENS_FACING); if(f!=null && f==CameraCharacteristics.LENS_FACING_BACK){chosen=id;break;} }
            if(chosen==null && cm.getCameraIdList().length>0) chosen=cm.getCameraIdList()[0]; if(chosen==null)return;
            cm.openCamera(chosen,new CameraDevice.StateCallback(){ public void onOpened(CameraDevice c){camera=c; createPreview();} public void onDisconnected(CameraDevice c){c.close();} public void onError(CameraDevice c,int e){c.close();}},camHandler);
        }catch(Exception e){ overlay.status="CAMERA: "+e.getClass().getSimpleName(); }
    }

    private void createPreview(){
        try{
            android.graphics.SurfaceTexture st=cameraView.getSurfaceTexture(); if(st==null)return; st.setDefaultBufferSize(1280,720); Surface sf=new Surface(st);
            final CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); b.addTarget(sf); b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Collections.singletonList(sf),new CameraCaptureSession.StateCallback(){ public void onConfigured(CameraCaptureSession s){session=s; try{session.setRepeatingRequest(b.build(),null,camHandler);}catch(Exception ignored){}} public void onConfigureFailed(CameraCaptureSession s){}},camHandler);
        }catch(Exception e){ overlay.status="PREVIEW: "+e.getClass().getSimpleName(); }
    }

    private void startVision(){
        if(visionRunning)return; visionRunning=true;
        visionExec.submit(()->{
            int[] prev=null; final int W=72,H=128; long next=0;
            while(visionRunning){
                try{
                    long now=SystemClock.elapsedRealtime(); if(now<next){Thread.sleep(12);continue;} next=now+85;
                    Bitmap frame=cameraView.getBitmap(W,H); if(frame==null)continue; int[] px=new int[W*H]; frame.getPixels(px,0,W,0,0,W,H); frame.recycle();
                    if(prev!=null){ ArrayList<float[]> pts=new ArrayList<>(); float sum=0; int count=0; for(int y=2;y<H-2;y+=2){ for(int x=2;x<W-2;x+=2){ int i=y*W+x; int c=px[i],p=prev[i]; int lum=((Color.red(c)*3+Color.green(c)*6+Color.blue(c))/10); int old=((Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10); int d=Math.abs(lum-old); if(d>22){ sum+=d; count++; if(d>38 && pts.size()<250) pts.add(new float[]{x/(float)W,y/(float)H,Math.min(1f,d/120f)}); } } }
                        float energy=Math.min(1f,sum/(W*H*8f)); overlay.pushMotion(pts,energy);
                    }
                    prev=px;
                }catch(Exception ignored){}
            }
        });
    }

    private void startAudio(){
        try{
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
            int sr=16000, min=AudioRecord.getMinBufferSize(sr,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT); int size=Math.max(min,2048);
            audio=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,sr,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2); if(audio.getState()!=AudioRecord.STATE_INITIALIZED) audio=new AudioRecord(MediaRecorder.AudioSource.MIC,sr,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2);
            audio.startRecording(); audioRunning=true; final int n=size;
            audioThread=new Thread(()->{ short[] buf=new short[n]; while(audioRunning){ int got=audio.read(buf,0,buf.length); if(got>0){ double ss=0; float peak=0; for(int i=0;i<got;i++){float v=buf[i]/32768f; ss+=v*v; peak=Math.max(peak,Math.abs(v));} float rms=(float)Math.sqrt(ss/got); float low=goertzel(buf,got,16000,120), mid=goertzel(buf,got,16000,800), high=goertzel(buf,got,16000,3200); overlay.setAudio(rms,peak,low,mid,high);} } },"omega-audio"); audioThread.start();
        }catch(Exception e){ overlay.status="AUDIO OFF"; }
    }

    private float goertzel(short[] x,int n,int sr,float f){ double w=2*Math.PI*f/sr, coeff=2*Math.cos(w),q0=0,q1=0,q2=0; int step=Math.max(1,n/512); int c=0; for(int i=0;i<n;i+=step){q0=coeff*q1-q2+x[i]/32768.0;q2=q1;q1=q0;c++;} double mag=Math.sqrt(q1*q1+q2*q2-coeff*q1*q2)/(Math.max(1,c)); return (float)Math.min(1,mag*4); }

    @Override protected void onPause(){ super.onPause(); if(sm!=null)sm.unregisterListener(this); }
    @Override protected void onResume(){ super.onResume(); if(sm!=null && accel!=null) startSensors(); }
    @Override protected void onDestroy(){ super.onDestroy(); visionRunning=false; visionExec.shutdownNow(); audioRunning=false; try{if(audio!=null){audio.stop();audio.release();}}catch(Exception ignored){} try{if(session!=null)session.close();if(camera!=null)camera.close();}catch(Exception ignored){} if(camThread!=null)camThread.quitSafely(); }

    public static class OmegaOverlay extends View {
        private final Paint p=new Paint(3), text=new Paint(3); private final ArrayDeque<ArrayList<float[]>> history=new ArrayDeque<>();
        volatile int mode=4; volatile String status="SENSORS ONLINE"; private float ax,ay,az,gx,gy,gz,mx,my,mz,mag,magD,lux,rms,peak,low,mid,high,motion;
        private float baseAudio=.01f, baseMagD=.2f; private long lastEvent=0; private int events=0; private String flash="Ω FUSION"; private long flashUntil=0;
        public OmegaOverlay(Context c){super(c); setLayerType(View.LAYER_TYPE_SOFTWARE,null); text.setTypeface(Typeface.MONOSPACE);}
        public synchronized void setSensors(float[] a,float[] g,float[] m,float mg,float md,float lx){ax=a[0];ay=a[1];az=a[2];gx=g[0];gy=g[1];gz=g[2];mx=m[0];my=m[1];mz=m[2];mag=mg;magD=md;lux=lx;baseMagD=.995f*baseMagD+.005f*md;postInvalidate();}
        public synchronized void setAudio(float r,float pk,float l,float mi,float h){rms=r;peak=pk;low=l;mid=mi;high=h;baseAudio=.995f*baseAudio+.005f*r;postInvalidate();}
        public synchronized void pushMotion(ArrayList<float[]> pts,float e){motion=.78f*motion+.22f*e; history.addFirst(pts); while(history.size()>12)history.removeLast(); postInvalidate();}
        public void flashLabel(String s){flash=s;flashUntil=SystemClock.elapsedRealtime()+900;invalidate();}

        @Override protected synchronized void onDraw(Canvas c){super.onDraw(c); int w=getWidth(),h=getHeight(); if(w==0||h==0)return;
            drawGrid(c,w,h);
            if(mode==0||mode==3||mode==4) drawEcho(c,w,h);
            if(mode==1||mode==3||mode==4) drawField(c,w,h);
            if(mode==2||mode==3||mode==4) drawSonic(c,w,h);
            drawHud(c,w,h);
            if(mode==4) detectEvent(c,w,h);
            if(SystemClock.elapsedRealtime()<flashUntil){text.setTextSize(34);text.setColor(Color.argb(230,255,255,255));text.setTextAlign(Paint.Align.CENTER);c.drawText(flash,w/2f,h*.5f,text);}
            postInvalidateDelayed(50);
        }
        private void drawGrid(Canvas c,int w,int h){p.setStrokeWidth(1);p.setColor(Color.argb(28,124,255,239)); for(int i=1;i<8;i++){float x=w*i/8f;c.drawLine(x,0,x,h,p);} for(int i=1;i<12;i++){float y=h*i/12f;c.drawLine(0,y,w,y,p);} p.setColor(Color.argb(70,124,255,239));c.drawCircle(w/2f,h/2f,Math.min(w,h)*.18f,p);c.drawLine(w*.47f,h/2f,w*.53f,h/2f,p);c.drawLine(w/2f,h*.47f,w/2f,h*.53f,p);}
        private void drawEcho(Canvas c,int w,int h){int age=0; for(ArrayList<float[]> layer:history){int alpha=Math.max(12,210-age*16); float rad=Math.max(2,9-age*.5f); for(float[] q:layer){float x=q[0]*w,y=q[1]*h; float k=q[2]; int rr=(int)(80+175*k), gg=(int)(255-80*k), bb=255; p.setColor(Color.argb((int)(alpha*k),rr,gg,bb));c.drawCircle(x,y,rad*(.4f+k),p);}age++;}}
        private void drawField(Canvas c,int w,int h){float cx=w*.5f,cy=h*.62f; float ang=(float)Math.atan2(my,mx); float strength=Math.min(1f,mag/100f); for(int i=0;i<10;i++){float r=w*(.08f+i*.035f); RectF o=new RectF(cx-r,cy-r,cx+r,cy+r);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1+i*.13f);p.setColor(Color.argb((int)(35+90*strength),124,255,239));c.save();c.rotate((float)Math.toDegrees(ang)+i*7,cx,cy);c.drawArc(o,200,140,false,p);c.drawArc(o,20,140,false,p);c.restore();}p.setStyle(Paint.Style.FILL); float len=w*.18f; p.setStrokeWidth(4);p.setColor(Color.argb(210,255,90,220));c.drawLine(cx,cy,cx+(float)Math.cos(ang)*len,cy+(float)Math.sin(ang)*len,p);}
        private void drawSonic(Canvas c,int w,int h){float y=h*.78f; int bars=48; for(int i=0;i<bars;i++){float x=i*w/(float)bars;float wave=(float)(Math.sin(i*.8+SystemClock.uptimeMillis()*.012)*.5+.5); float e=(rms*5f)*(0.3f+wave*.7f);p.setColor(Color.argb(160,150,120+(int)(120*wave),255));p.setStrokeWidth(Math.max(2,w/bars*.55f));c.drawLine(x,y,x,y-e*h*.12f,p);} float[] bands={low,mid,high}; for(int i=0;i<3;i++){p.setColor(Color.argb(170,124+i*50,255-i*50,239));c.drawRect(w*.05f+i*w*.31f,h*.86f,w*.05f+i*w*.31f+w*.26f,h*.86f-bands[i]*h*.08f,p);}}
        private void drawHud(Canvas c,int w,int h){text.setTextAlign(Paint.Align.LEFT);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(12);text.setColor(Color.argb(225,205,255,248)); float y=92; c.drawText(String.format(Locale.US,"B-field  %6.1f µT   Δ %.2f",mag,magD),16,y,text); y+=18;c.drawText(String.format(Locale.US,"motion   %5.2f      sonic %.3f",motion,rms),16,y,text); y+=18;c.drawText(String.format(Locale.US,"gyro     %+.2f %+.2f %+.2f",gx,gy,gz),16,y,text); y+=18;c.drawText(String.format(Locale.US,"light    %.0f lux     events %02d",lux,events),16,y,text); text.setColor(Color.argb(160,180,255,244));text.setTextSize(10);c.drawText(status,16,y+18,text);
            String mn=mode==0?"TEMPORAL ECHO":mode==1?"MAGNETIC FIELD":mode==2?"SONIC VISION":mode==3?"FUSION":"Ω COINCIDENCE WATCH";text.setTextAlign(Paint.Align.RIGHT);text.setTextSize(11);text.setColor(Color.rgb(124,255,239));c.drawText(mn,w-16,92,text);}
        private void detectEvent(Canvas c,int w,int h){float audioScore=Math.max(0,(rms-baseAudio*1.6f)*12f);float magScore=Math.max(0,(magD-baseMagD*1.5f)*.8f);float score=Math.min(1f,motion*1.7f+audioScore+magScore); boolean triad=motion>.08f && rms>Math.max(.018f,baseAudio*1.6f) && magD>Math.max(.45f,baseMagD*1.6f); long now=SystemClock.elapsedRealtime(); if(triad && now-lastEvent>2200){lastEvent=now;events++;flash="Ω EVENT "+String.format(Locale.US,"%02d",events);flashUntil=now+1200;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);} p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.argb((int)(40+190*score),255,80,220));float r=Math.min(w,h)*(.24f+.08f*score);c.drawCircle(w/2f,h/2f,r,p);p.setStyle(Paint.Style.FILL);text.setTextAlign(Paint.Align.CENTER);text.setTextSize(10);text.setColor(Color.argb(180,255,170,240));c.drawText(String.format(Locale.US,"COINCIDENCE %.0f%%",score*100),w/2f,h*.57f,text);}
    }
}
