package com.omega.projector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class HoloCanvasView extends View {
    public static final int MODE_HOLO4 = 2;
    public static final int MODE_GHOST = 3;

    private Bitmap frame;
    private int mode = MODE_HOLO4;
    private float holoSize = 0.82f;
    private float brightness = 1f;
    private float contrast = 1f;
    private boolean mirror = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HoloCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f);
        guidePaint.setColor(Color.argb(70, 90, 230, 255));
    }

    public void setFrame(Bitmap bitmap) {
        Bitmap old = this.frame;
        this.frame = bitmap;
        invalidate();
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    public void setMode(int mode) { this.mode = mode; invalidate(); }
    public void setHoloSize(float value) { holoSize = Math.max(0.35f, Math.min(1.2f, value)); invalidate(); }
    public void setMirror(boolean value) { mirror = value; invalidate(); }
    public void setBrightness(float value) { brightness = value; rebuildFilter(); invalidate(); }
    public void setContrast(float value) { contrast = value; rebuildFilter(); invalidate(); }

    private void rebuildFilter() {
        float c = Math.max(0.2f, contrast);
        float b = (brightness - 1f) * 255f;
        float t = 128f * (1f - c) + b;
        ColorMatrix cm = new ColorMatrix(new float[]{
                c,0,0,0,t,
                0,c,0,0,t,
                0,0,c,0,t,
                0,0,0,1,0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (frame == null || frame.isRecycled() || getWidth() < 2 || getHeight() < 2) return;

        if (mode == MODE_GHOST) drawGhost(canvas);
        else drawFourWay(canvas);
    }

    private void drawGhost(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float targetW = w * 0.82f * holoSize;
        float targetH = Math.min(h * 0.56f, targetW * ((float) frame.getHeight() / Math.max(1, frame.getWidth())));
        RectF dst = new RectF((w-targetW)/2f, h*0.18f, (w+targetW)/2f, h*0.18f+targetH);
        drawInto(canvas, dst, 0f);
    }

    private void drawFourWay(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w/2f;
        float cy = h/2f;
        float base = Math.min(w, h) * 0.30f * holoSize;
        float aspect = (float) frame.getHeight() / Math.max(1, frame.getWidth());
        float rw = base;
        float rh = Math.min(base * 0.68f, base * aspect);
        float gap = Math.max(8f, Math.min(w,h) * 0.035f);

        RectF bottom = new RectF(cx-rw/2f, cy+gap, cx+rw/2f, cy+gap+rh);
        RectF top = new RectF(cx-rw/2f, cy-gap-rh, cx+rw/2f, cy-gap);
        RectF left = new RectF(cx-gap-rh, cy-rw/2f, cx-gap, cy+rw/2f);
        RectF right = new RectF(cx+gap, cy-rw/2f, cx+gap+rh, cy+rw/2f);

        drawInto(canvas, bottom, 0f);
        drawInto(canvas, top, 180f);
        drawInto(canvas, left, 90f);
        drawInto(canvas, right, -90f);

        float core = gap * 1.55f;
        canvas.drawRect(cx-core, cy-core, cx+core, cy+core, guidePaint);
    }

    private void drawInto(Canvas canvas, RectF dst, float rotation) {
        int save = canvas.save();
        canvas.rotate(rotation, dst.centerX(), dst.centerY());
        if (mirror) {
            canvas.scale(-1f, 1f, dst.centerX(), dst.centerY());
        }
        RectF src = cropFor(dst);
        canvas.drawBitmap(frame, src, dst, paint);
        canvas.restoreToCount(save);
    }

    private RectF cropFor(RectF dst) {
        float srcW = frame.getWidth();
        float srcH = frame.getHeight();
        float srcAspect = srcW / Math.max(1f, srcH);
        float dstAspect = dst.width() / Math.max(1f, dst.height());
        if (srcAspect > dstAspect) {
            float useW = srcH * dstAspect;
            float x = (srcW - useW)/2f;
            return new RectF(x,0,x+useW,srcH);
        } else {
            float useH = srcW / Math.max(0.01f, dstAspect);
            float y = (srcH - useH)/2f;
            return new RectF(0,y,srcW,y+useH);
        }
    }
}
