package com.omega.synesthesia;
import android.content.*; import android.graphics.*; import android.view.*;

public class SynesthesiaView extends View {
 private final Paint p=new Paint(3); private float coherence,novelty,intensity,phase;
 public SynesthesiaView(Context c){super(c);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));}
 public void setPercept(FusionEngine.Percept x){coherence=x.coherence;novelty=x.novelty;intensity=x.intensity;phase+=.12f;invalidate();}
 protected void onDraw(Canvas c){super.onDraw(c); float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2;
  c.drawColor(Color.rgb(5,7,11));
  for(int i=7;i>=1;i--){float r=Math.min(w,h)*(.055f*i+.025f*intensity*(float)Math.sin(phase+i)); int a=(int)(20+coherence*30); p.setColor(Color.argb(a,70+(int)(novelty*150),240,220));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2+i*.6f);c.drawCircle(cx,cy,r,p);}
  float core=32+intensity*95; p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(180,80+(int)(novelty*170),245,225));c.drawCircle(cx,cy,core,p);
  p.setTextAlign(Paint.Align.CENTER);p.setTextSize(23);p.setColor(Color.WHITE);c.drawText("ONE FIELD • MANY FORMS",cx,h-34,p);
 }
}
