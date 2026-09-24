package com.vaan.behindthecurtain

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class ScreenCaptureService:Service(){
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var vd:android.hardware.display.VirtualDisplay?=null
    private var wm:WindowManager?=null
    private var overlay:OverlayView?=null
    private val ex=Executors.newSingleThreadExecutor()
    private val tracker=Tracker()
    private val temporal=TemporalEngine()
    private var last=0L
    private var lastOcr=0L
    private var lastTpl=0L
    private var ocr:List<Detection> = emptyList()
    private var tpl:List<Detection> = emptyList()

    override fun onCreate(){
        super.onCreate()
        channel()
        startForeground(401,note("Screen Sentinel scanning"))
        if(Settings.canDrawOverlays(this)) makeOverlay()
    }

    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int{
        val code=i?.getIntExtra("code",Activity.RESULT_CANCELED)?:return START_NOT_STICKY
        val data=if(Build.VERSION.SDK_INT>=33) i.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra("data")
        if(data==null)return START_NOT_STICKY
        projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},Handler(Looper.getMainLooper()))
        startDisplay()
        return START_NOT_STICKY
    }

    private fun startDisplay(){
        val b=getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w=b.width().coerceAtLeast(1)
        val h=b.height().coerceAtLeast(1)
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        vd=projection?.createVirtualDisplay("BTC-screen",w,h,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
        reader!!.setOnImageAvailableListener({r->
            val now=System.currentTimeMillis()
            val im=r.acquireLatestImage()?:return@setOnImageAvailableListener
            if(now-last<220){im.close();return@setOnImageAvailableListener}
            last=now
            val p=im.planes[0];val buf=p.buffer;val ps=p.pixelStride;val rs=p.rowStride;val pad=rs-ps*w;val pw=w+pad/ps
            val t=Bitmap.createBitmap(pw,h,Bitmap.Config.ARGB_8888);t.copyPixelsFromBuffer(buf);im.close()
            val bmp=Bitmap.createBitmap(t,0,0,w,h);t.recycle()
            ex.execute{
                if(!AppState.master(this)){overlay?.post{overlay?.ds=emptyList();overlay?.subtitles=listOf("PAUSED");overlay?.invalidate()};return@execute}
                if(now-lastOcr>950){lastOcr=now;ocr=OcrEngine.scan(bmp,true)}
                if(now-lastTpl>1450){lastTpl=now;tpl=TemplateMatcher.scan(bmp,TemplateStore.load(this))}
                val ds=tracker.update((VisualEngine.scan(bmp)+temporal.scan(bmp)+ocr+tpl).sortedByDescending{it.confidence}.take(30))
                val subs=ds.mapNotNull{when{it.label.startsWith("TEXT ALT:")->"ALT: "+it.label.removePrefix("TEXT ALT:").trim();it.label.startsWith("TEXT:")->it.label.removePrefix("TEXT:").trim();else->null}}.distinct().take(4)
                overlay?.post{overlay?.sourceW=w;overlay?.sourceH=h;overlay?.ds=ds;overlay?.subtitles=if(AppState.subtitles(this))subs else emptyList();overlay?.invalidate()}
                EvidenceStore.visual(this,bmp,ds)
            }
        },Handler(Looper.getMainLooper()))
    }

    private fun makeOverlay(){
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        overlay=OverlayView(this)
        wm?.addView(overlay,WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START})
    }
    private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("btc_screen","Behind the Curtain screen scan",NotificationManager.IMPORTANCE_LOW))}
    private fun note(s:String)=NotificationCompat.Builder(this,"btc_screen").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Behind the Curtain").setContentText(s).setOngoing(true).build()
    override fun onDestroy(){try{overlay?.let{wm?.removeView(it)}}catch(_:Throwable){};reader?.close();vd?.release();projection?.stop();ex.shutdownNow();super.onDestroy()}
    override fun onBind(i:Intent?)=null
}
