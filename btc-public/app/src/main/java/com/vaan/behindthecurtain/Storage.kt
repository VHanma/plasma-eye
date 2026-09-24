package com.vaan.behindthecurtain

import android.content.*
import android.graphics.*
import android.os.Build
import android.provider.MediaStore
import org.json.*
import java.io.ByteArrayOutputStream
import java.nio.*
import java.text.SimpleDateFormat
import java.util.*

object EvidenceStore {
    private val day=SimpleDateFormat("yyyy-MM-dd",Locale.US);private val stamp=SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US);@Volatile private var lv=0L;@Volatile private var la=0L
    fun visual(c:Context,b:Bitmap,ds:List<Detection>){val now=System.currentTimeMillis();if(now-lv<2200||ds.none{it.confidence>=.68f})return;lv=now;val tag=stamp.format(Date(now));val e=ImageFx.enhance(b);val m=b.copy(Bitmap.Config.ARGB_8888,true);val ca=Canvas(m);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=maxOf(3f,b.width/300f);color=Color.CYAN};ds.forEach{ca.drawRect(it.rect,p)};bitmap(c,"BTC_${tag}_raw.jpg",b);bitmap(c,"BTC_${tag}_enhanced.jpg",e);bitmap(c,"BTC_${tag}_marked.jpg",m);val a=JSONArray();ds.forEach{d->a.put(JSONObject().put("id",d.id).put("label",d.label).put("confidence",d.confidence).put("left",d.rect.left).put("top",d.rect.top).put("right",d.rect.right).put("bottom",d.rect.bottom))};bytes(c,"BTC_$tag.json","application/json",JSONObject().put("type","visual").put("timestamp_ms",now).put("detections",a).toString(2).toByteArray())}
    fun audio(c:Context,pcm:ShortArray,rate:Int,f:AudioFinding,words:List<String>){val now=System.currentTimeMillis();if(now-la<4500)return;la=now;val tag=stamp.format(Date(now));bytes(c,"BTC_$tag.wav","audio/wav",wav(pcm,rate));bytes(c,"BTC_$tag.json","application/json",JSONObject().put("type","audio").put("timestamp_ms",now).put("label",f.label).put("confidence",f.confidence).put("peak_hz",f.peakHz).put("rms_db",f.rmsDb).put("detail",f.detail).put("candidate_words",JSONArray(words)).toString(2).toByteArray())}
    private fun bitmap(c:Context,n:String,b:Bitmap){val o=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,94,o);bytes(c,n,"image/jpeg",o.toByteArray())}
    private fun bytes(c:Context,n:String,m:String,d:ByteArray){if(Build.VERSION.SDK_INT>=29){val v=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,n);put(MediaStore.MediaColumns.MIME_TYPE,m);put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/BehindTheCurtain/${day.format(Date())}/");put(MediaStore.MediaColumns.IS_PENDING,1)};val u=c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:return;c.contentResolver.openOutputStream(u)?.use{it.write(d)};v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);c.contentResolver.update(u,v,null,null)}else{val f=c.getExternalFilesDir("BehindTheCurtain")?:return;f.mkdirs();java.io.File(f,n).writeBytes(d)}}
    private fun wav(p:ShortArray,r:Int):ByteArray{val dl=p.size*2;val b=ByteBuffer.allocate(44+dl).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".toByteArray());b.putInt(36+dl);b.put("WAVE".toByteArray());b.put("fmt ".toByteArray());b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(r);b.putInt(r*2);b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(dl);p.forEach{b.putShort(it)};return b.array()}
}
