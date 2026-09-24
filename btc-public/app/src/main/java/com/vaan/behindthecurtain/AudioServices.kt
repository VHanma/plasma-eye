package com.vaan.behindthecurtain

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.*
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class SubtitleOverlay(private val c:Context){
    private var wm:WindowManager?=null
    private var v:TextView?=null
    fun show(lines:List<String>){
        if(!AppState.subtitles(c)||!Settings.canDrawOverlays(c))return
        val s=lines.filter{it.isNotBlank()}.take(4).joinToString("\n")
        if(s.isBlank())return
        if(v==null){
            wm=c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            v=TextView(c).apply{setTextColor(Color.WHITE);setBackgroundColor(Color.argb(215,0,0,0));textSize=16f;gravity=Gravity.CENTER;setPadding(20,12,20,12)}
            wm?.addView(v,WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=80})
        }
        v?.text=s
    }
    fun hide(){try{v?.let{wm?.removeView(it)}}catch(_:Throwable){};v=null}
}

class AudioScanService:Service(){
    private val ex=Executors.newSingleThreadExecutor()
    private var rec:AudioRecord?=null
    @Volatile private var running=false
    @Volatile private var speechBusy=false
    private var sampleRate=48000
    private lateinit var ring:PcmRing
    private var lastSignal=0L
    private var lastSpeech=0L
    private lateinit var subs:SubtitleOverlay

    override fun onCreate(){
        super.onCreate()
        subs=SubtitleOverlay(this)
        channel()
        startForeground(402,note("Audio Sentinel scanning"))
        ex.execute{loop()}
    }

    private fun chooseRate():Int{
        for(r in intArrayOf(96000,48000,44100)){
            if(AudioRecord.getMinBufferSize(r,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)>0)return r
        }
        return 44100
    }

    private fun loop(){
        sampleRate=chooseRate()
        ring=PcmRing(sampleRate*12)
        val min=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val f=AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        rec=try{AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(f).setBufferSizeInBytes(min*4).build()}catch(_:Throwable){AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(f).setBufferSizeInBytes(min*4).build()}
        val r=rec?:return
        val block=ShortArray(8192)
        running=true
        r.startRecording()
        while(running){
            val n=r.read(block,0,block.size,AudioRecord.READ_BLOCKING)
            if(n<=0)continue
            if(!AppState.master(this))continue
            val x=if(n==block.size)block.copyOf()else block.copyOf(n)
            ring.add(x)
            val now=System.currentTimeMillis()
            val finding=AudioInspector.scan(x,sampleRate)

            if(finding!=null&&now-lastSignal>=5000){
                lastSignal=now
                val ev=ring.snap()
                if(!speechBusy){
                    speechBusy=true
                    lastSpeech=now
                    SpeechDecoder.decode(this,ev,sampleRate){words->
                        speechBusy=false
                        EvidenceStore.audio(this,ev,sampleRate,finding,words)
                        alert(finding,words)
                        subs.show(if(words.isEmpty())listOf(signalText(finding))else words)
                    }
                }else{
                    EvidenceStore.audio(this,ev,sampleRate,finding,emptyList())
                    alert(finding,emptyList())
                    subs.show(listOf(signalText(finding)))
                }
                continue
            }

            if(!speechBusy&&now-lastSpeech>=12000){
                val ev=ring.snap()
                if(ev.size>=sampleRate*3){
                    lastSpeech=now
                    speechBusy=true
                    SpeechDecoder.decode(this,ev,sampleRate){words->
                        speechBusy=false
                        if(words.isNotEmpty()){
                            val speech=AudioFinding("SPEECH / TRANSFORM CANDIDATE",0.70f,0f,-120f,"periodic forward, reversed and speed-shift speech sweep")
                            EvidenceStore.audio(this,ev,sampleRate,speech,words)
                            alert(speech,words)
                            subs.show(words)
                        }
                    }
                }
            }
        }
    }

    private fun signalText(f:AudioFinding)=if(f.peakHz>0.1f)"${f.label} • ${"%.1f".format(f.peakHz)} Hz" else f.label

    private fun alert(f:AudioFinding,w:List<String>){
        val text=signalText(f)
        val b=NotificationCompat.Builder(this,"btc_audio").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Behind the Curtain detected audio").setContentText(if(w.isEmpty())text else "$text • ${w.first().take(70)}").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if(w.isNotEmpty())b.setStyle(NotificationCompat.BigTextStyle().bigText(w.take(6).joinToString("\n")))
        getSystemService(NotificationManager::class.java).notify(403,b.build())
    }
    private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("btc_audio","Behind the Curtain audio alerts",NotificationManager.IMPORTANCE_HIGH))}
    private fun note(s:String)=NotificationCompat.Builder(this,"btc_audio").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Behind the Curtain").setContentText(s).setOngoing(true).build()
    override fun onDestroy(){running=false;subs.hide();try{rec?.stop()}catch(_:Throwable){};rec?.release();ex.shutdownNow();super.onDestroy()}
    override fun onBind(i:Intent?)=null
}
