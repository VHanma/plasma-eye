package com.omega.synesthesia;

public final class CrossSenseEngine {
    public static final class Prediction {
        public final float predictedVisual, predictedAudio, visualAgreement, audioAgreement;
        Prediction(float pv,float pa,float va,float aa){predictedVisual=pv;predictedAudio=pa;visualAgreement=va;audioAgreement=aa;}
    }

    private final float[] wVisual = new float[5];
    private final float[] wAudio = new float[5];
    private int samples = 0;
    private float emaVisualError = .25f, emaAudioError = .25f;

    public synchronized Prediction update(float visual, float audio, float vibration, float rotation, float magnetic, float light) {
        float[] xv = norm(new float[]{audio,vibration,rotation,magnetic,light});
        float[] xa = norm(new float[]{visual,vibration,rotation,magnetic,light});
        float pv = clamp(dot(wVisual,xv));
        float pa = clamp(dot(wAudio,xa));
        float ev = visual - pv;
        float ea = audio - pa;
        float lr = samples < 300 ? .035f : .008f;
        for(int i=0;i<5;i++){wVisual[i]+=lr*ev*xv[i];wAudio[i]+=lr*ea*xa[i];}
        emaVisualError = emaVisualError*.96f + Math.abs(ev)*.04f;
        emaAudioError = emaAudioError*.96f + Math.abs(ea)*.04f;
        samples++;
        float va = samples < 80 ? 0f : clamp(1f - emaVisualError*3.5f);
        float aa = samples < 80 ? 0f : clamp(1f - emaAudioError*3.5f);
        return new Prediction(pv,pa,va,aa);
    }

    private static float[] norm(float[] x){
        float[] y=new float[x.length];
        for(int i=0;i<x.length;i++) y[i]=(float)Math.tanh(x[i]*4f);
        return y;
    }
    private static float dot(float[] w,float[] x){float s=0;for(int i=0;i<w.length;i++)s+=w[i]*x[i];return s;}
    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
}
