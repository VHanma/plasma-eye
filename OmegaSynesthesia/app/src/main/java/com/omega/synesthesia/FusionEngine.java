package com.omega.synesthesia;

import java.util.*;

public final class FusionEngine {
 public static final class Percept {
  public final String statement, detail; public final float coherence, novelty, intensity;
  Percept(String s,String d,float c,float n,float i){statement=s;detail=d;coherence=c;novelty=n;intensity=i;}
 }
 private static final int N=6;
 private final double[] mean=new double[N], var=new double[N], lastZ=new double[N];
 private long count=0; private float smooth=0, noveltySmooth=0;

 public synchronized Percept fuse(float visualMotion,float audioEnergy,float vibration,float rotation,float magnetic,float lightChange){
  double[] x={visualMotion,audioEnergy,vibration,rotation,magnetic,lightChange}; count++;
  double sum=0, agree=0, max=0; int active=0;
  for(int i=0;i<N;i++){
   if(count<80){ double d=x[i]-mean[i]; mean[i]+=d/count; var[i]+=d*(x[i]-mean[i]); lastZ[i]=0; continue; }
   double sd=Math.sqrt(Math.max(1e-7,var[i]/Math.max(1,count-1))); double z=Math.abs(x[i]-mean[i])/sd; z=Math.min(z,12); lastZ[i]=z;
   if(z<1.4){ mean[i]=mean[i]*.998+x[i]*.002; }
   double a=Math.max(0,z-1.15); sum+=a; max=Math.max(max,a); if(a>1) active++;
  }
  if(count<80) return new Percept("LEARNING THIS ENVIRONMENT","Keep the phone steady. I am learning what normal feels like.",0,0,0);
  double avg=sum/N; for(double z:lastZ) if(z>1.5 && avg>.4) agree++;
  float coherence=(float)Math.min(1,(agree/N)*.7+Math.min(1,avg/4)*.3);
  float intensity=(float)Math.min(1,avg/5); smooth=smooth*.86f+intensity*.14f;
  float novelty=(float)Math.min(1,(max/(1+avg))*0.35 + (active>=3?0.25:0)); noveltySmooth=noveltySmooth*.9f+novelty*.1f;
  if(smooth<.10f) return new Percept("ENVIRONMENT QUIET","No coherent event stands out from the learned background.",coherence,noveltySmooth,smooth);
  String[] names={"vision","sound","vibration","rotation","magnetic response","light"};
  List<String> strong=new ArrayList<>(); for(int i=0;i<N;i++) if(lastZ[i]>2.0) strong.add(names[i]);
  String channels=strong.isEmpty()?"one weak pathway":String.join(" + ",strong);
  if(coherence>.48f && noveltySmooth>.35f) return new Percept("UNKNOWN COHERENT EVENT","I feel one event crossing "+channels+". It does not fit the current background.",coherence,noveltySmooth,smooth);
  if(coherence>.34f) return new Percept("COHERENT EVENT DETECTED","Several forms of perception agree: "+channels+".",coherence,noveltySmooth,smooth);
  return new Percept("POSSIBLE EVENT","Something changed through "+channels+", but the senses do not agree strongly yet.",coherence,noveltySmooth,smooth);
 }
}
