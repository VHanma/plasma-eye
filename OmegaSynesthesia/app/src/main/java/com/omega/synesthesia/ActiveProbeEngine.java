package com.omega.synesthesia;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

public final class ActiveProbeEngine {
    public interface Listener { void onProbe(Reading r); }
    public static final class Reading {
        public final float response, change, confidence;
        public final long roundTripMs;
        Reading(float r,float c,float q,long ms){response=r;change=c;confidence=q;roundTripMs=ms;}
    }
    private static final int RATE=48000;
    private volatile boolean probing;
    private float baseline;
    private int count;

    public boolean isProbing(){return probing;}

    public synchronized void probe(final Listener listener){
        if(probing)return; probing=true;
        new Thread(() -> {
            long start=System.nanoTime();
            try{
                short[] wave=makeProbe();
                AudioTrack t=new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(wave.length*2).setTransferMode(AudioTrack.MODE_STATIC).build();
                t.write(wave,0,wave.length);t.play();
                Thread.sleep(95);t.stop();t.release();
                long ms=(System.nanoTime()-start)/1_000_000L;
                if(listener!=null)listener.onProbe(new Reading(0,0,0,ms));
            }catch(Exception ignored){if(listener!=null)listener.onProbe(new Reading(0,0,0,0));}
            probing=false;
        },"OmegaProbe").start();
    }

    public synchronized Reading score(float before,float after,long ms){
        float response=Math.max(0,after-before);
        if(count<8){baseline+=response;count++;return new Reading(response,0,.15f,ms);}
        float b=baseline/Math.max(1,count);float change=Math.abs(response-b)/(b+.002f);
        float conf=Math.min(1f,change*.65f);
        if(change<.35f){baseline=baseline*.98f+response*.02f*Math.max(1,count);}
        return new Reading(response,change,conf,ms);
    }

    private short[] makeProbe(){
        int n=(int)(RATE*.075);short[] out=new short[n];
        double phase=0;
        for(int i=0;i<n;i++){
            double u=i/(double)Math.max(1,n-1);
            double f=15500+3500*u;
            phase+=2*Math.PI*f/RATE;
            double win=Math.sin(Math.PI*u);win*=win;
            double code=((i/96)%2==0)?1.0:-1.0;
            out[i]=(short)(Math.sin(phase)*win*code*5200);
        }
        return out;
    }
}
