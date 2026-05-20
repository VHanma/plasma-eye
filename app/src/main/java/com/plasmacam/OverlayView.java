package com.plasmacam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class OverlayView extends View {
    private volatile int[] pixels;
    private volatile int frameW, frameH;

    public OverlayView(Context ctx, AttributeSet attrs) { super(ctx, attrs); }

    public void setFrame(int[] px, int w, int h) {
        pixels = px; frameW = w; frameH = h;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int[] px = pixels;
        int w = frameW, h = frameH;
        if (px == null || w == 0 || h == 0) return;
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        canvas.drawBitmap(bmp, null, new RectF(0, 0, getWidth(), getHeight()), null);
        bmp.recycle();
    }
}
