package com.omega.synesthesia;

public final class EchoGeometryEngine {
 public static final class Reading {
  public final float near, mid, far, drift, persistence;
  public final String zone;
  Reading(float n,float m,float f,float d,float p,String z){near=n;mid=m;far=f;drift=d;persistence=p;zone=z;}
 }
 private float nearBase,midBase,farBase; private int learn; private float lastDominant,persist;
 public synchronized Reading update(float early,float middle,float late,float visualLeft,float visualRight){
  if(learn<24){nearBase+=early;midBase+=middle;farBase+=late;learn++;return new Reading(0,0,0,0,0,"LEARNING ECHO SPACE");}
  float nb=nearBase/learn,mb=midBase/learn,fb=farBase/learn;
  float n=Math.abs(early-nb)/(nb+.002f),m=Math.abs(middle-mb)/(mb+.002f),f=Math.abs(late-fb)/(fb+.002f);
  float dominant=n>m&&n>f?0:n>f?0.5f:1f;float drift=dominant-lastDominant;lastDominant=lastDominant*.75f+dominant*.25f;
  float max=Math.max(n,Math.max(m,f));persist=max>.35f?Math.min(1,persist+.12f):Math.max(0,persist-.06f);
  String depth=dominant<.25f?"NEAR FIELD":dominant<.72f?"MID FIELD":"FAR FIELD";
  String side=Math.abs(visualLeft-visualRight)<.035f?"":visualLeft>visualRight?" • LEFT visual agreement":" • RIGHT visual agreement";
  if(max<.25f)depth="SPACE STABLE";return new Reading(n,m,f,drift,persist,depth+side);
 }
}
