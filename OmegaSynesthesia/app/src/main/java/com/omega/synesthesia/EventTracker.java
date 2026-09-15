package com.omega.synesthesia;
import java.util.*;
public final class EventTracker {
 public static final class Track { public final String id,zone,state;public final float strength,persistence;public final int age;Track(String i,String z,String s,float q,float p,int a){id=i;zone=z;state=s;strength=q;persistence=p;age=a;} }
 private static final class M {String id,zone;float sig,strength,persist;int age,miss;M(String i,String z,float s,float q){id=i;zone=z;sig=s;strength=q;persist=.2f;}}
 private final ArrayList<M> tracks=new ArrayList<>();private int next=1;
 public synchronized Track update(String zone,float strength,float visual,float audio,float magnetic){float sig=visual*.36f+audio*.34f+magnetic*.02f+strength*.28f;M best=null;float bd=999;for(M m:tracks){float d=Math.abs(m.sig-sig)+(m.zone.equals(zone)?0:.35f);if(d<bd){bd=d;best=m;}}if(best==null||bd>.62f){best=new M(String.format(Locale.US,"Ω-%03d",next++),zone,sig,strength);tracks.add(best);}best.zone=zone;best.sig=best.sig*.72f+sig*.28f;best.strength=best.strength*.65f+strength*.35f;best.persist=Math.min(1,best.persist+.12f);best.age++;best.miss=0;for(M m:tracks)if(m!=best){m.miss++;m.persist=Math.max(0,m.persist-.045f);}tracks.removeIf(m->m.miss>45);String st=best.age<4?"NEW":best.persist>.65f?"TRACKED":"REACQUIRING";return new Track(best.id,best.zone,st,best.strength,best.persist,best.age);}
 public synchronized Track coast(){M b=null;for(M m:tracks)if(b==null||m.persist>b.persist)b=m;if(b==null)return null;b.miss++;b.persist=Math.max(0,b.persist-.035f);if(b.persist<=.05f)return null;return new Track(b.id,b.zone,"COASTING",b.strength,b.persist,b.age);}
}
