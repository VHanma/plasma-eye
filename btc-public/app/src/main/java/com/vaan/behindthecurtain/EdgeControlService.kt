package com.vaan.behindthecurtain

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class EdgeControlService:Service(){
    private lateinit var wm:WindowManager
    private lateinit var root:LinearLayout
    private lateinit var handle:TextView
    private lateinit var panel:LinearLayout
    private lateinit var lp:WindowManager.LayoutParams
    private var dx=0f;private var dy=0f;private var sx=0;private var sy=0;private var moved=false;private var tiny=false

    override fun onCreate(){
        super.onCreate()
        channel()
        startForeground(410,NotificationCompat.Builder(this,"btc_widget").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Behind the Curtain").setContentText("Edge control ready").setOngoing(true).build())
        if(!Settings.canDrawOverlays(this)){stopSelf();return}
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        make()
    }

    private fun make(){
        root=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        handle=TextView(this).apply{text=if(AppState.master(this@EdgeControlService))"◉" else "○";textSize=26f;gravity=Gravity.CENTER;setTextColor(if(AppState.master(this@EdgeControlService))Color.CYAN else Color.GRAY);setBackgroundColor(Color.argb(150,0,0,0));setPadding(9,7,9,7)}
        panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setBackgroundColor(Color.argb(230,7,10,12));setPadding(10,10,10,10)}
        val master=Button(this).apply{
            text=if(AppState.master(this@EdgeControlService))"TURN SCANNER OFF" else "TURN SCANNER ON"
            setOnClickListener{
                val v=!AppState.master(this@EdgeControlService)
                AppState.setMaster(this@EdgeControlService,v)
                text=if(v)"TURN SCANNER OFF" else "TURN SCANNER ON"
                handle.text=if(v)"◉" else "○"
                handle.setTextColor(if(v)Color.CYAN else Color.GRAY)
            }
        }
        fun btn(s:String,cl:Class<*>):Button=Button(this).apply{text=s;setOnClickListener{startActivity(Intent(this@EdgeControlService,cl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));panel.visibility=View.GONE}}
        panel.addView(master)
        panel.addView(btn("CAMERA",CameraScannerActivity::class.java))
        panel.addView(btn("SCREEN",ScreenScannerActivity::class.java))
        panel.addView(btn("AUDIO",AudioScannerActivity::class.java))
        panel.addView(btn("VAULT",VaultActivity::class.java))
        panel.addView(btn("OPEN APP",MainActivity::class.java))
        root.addView(handle,LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,52)))
        root.addView(panel,LinearLayout.LayoutParams(Ui.dp(this,175),-2))
        lp=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=0;y=resources.displayMetrics.heightPixels/3}
        handle.setOnTouchListener{_,e->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{dx=e.rawX;dy=e.rawY;sx=lp.x;sy=lp.y;moved=false;true}
                MotionEvent.ACTION_MOVE->{val mx=(e.rawX-dx).toInt();val my=(e.rawY-dy).toInt();if(kotlin.math.abs(mx)>6||kotlin.math.abs(my)>6)moved=true;lp.x=sx+mx;lp.y=(sy+my).coerceAtLeast(0);wm.updateViewLayout(root,lp);true}
                MotionEvent.ACTION_UP->{if(!moved){if(tiny)expand()else panel.visibility=if(panel.visibility==View.VISIBLE)View.GONE else View.VISIBLE}else snap();true}
                else->false
            }
        }
        wm.addView(root,lp)
    }

    private fun snap(){
        val sw=resources.displayMetrics.widthPixels
        val rw=root.width.coerceAtLeast(Ui.dp(this,52))
        val left=lp.x<=Ui.dp(this,18)
        val right=lp.x+rw>=sw-Ui.dp(this,18)
        if(left||right){collapse(right);return}
        lp.x=if(lp.x+rw/2<sw/2)0 else (sw-rw).coerceAtLeast(0)
        wm.updateViewLayout(root,lp)
    }
    private fun collapse(right:Boolean){panel.visibility=View.GONE;tiny=true;handle.text="│";handle.textSize=23f;handle.layoutParams=handle.layoutParams.apply{width=Ui.dp(this@EdgeControlService,9);height=Ui.dp(this@EdgeControlService,58)};lp.x=if(right)resources.displayMetrics.widthPixels-Ui.dp(this,9)else 0;wm.updateViewLayout(root,lp)}
    private fun expand(){tiny=false;handle.text=if(AppState.master(this))"◉" else "○";handle.textSize=26f;handle.layoutParams=handle.layoutParams.apply{width=Ui.dp(this@EdgeControlService,52);height=Ui.dp(this@EdgeControlService,52)};lp.x=if(lp.x>resources.displayMetrics.widthPixels/2)resources.displayMetrics.widthPixels-Ui.dp(this,52)else 0;panel.visibility=View.VISIBLE;wm.updateViewLayout(root,lp)}
    private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("btc_widget","Behind the Curtain edge control",NotificationManager.IMPORTANCE_MIN))}
    override fun onDestroy(){try{wm.removeView(root)}catch(_:Throwable){};super.onDestroy()}
    override fun onBind(i:Intent?)=null
}
