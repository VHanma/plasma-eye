package com.omega.cornersight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class RoiOverlayView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF roi = new RectF(0.12f, 0.52f, 0.88f, 0.92f);
    private boolean dual = false;
    private float lastX, lastY;

    public RoiOverlayView(Context c) { super(c); setBackgroundColor(Color.TRANSPARENT); }

    public RectF getNormalizedRoi() { return new RectF(roi); }
    public void setDual(boolean v) { dual = v; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float l = roi.left * getWidth(), t = roi.top * getHeight();
        float r = roi.right * getWidth(), b = roi.bottom * getHeight();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.CYAN);
        c.drawRect(l, t, r, b, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x3300FFFF);
        c.drawRect(l, t, r, b, p);
        if (dual) {
            float m = (l + r) * 0.5f;
            p.setColor(0xAAFFCC33);
            p.setStrokeWidth(3f);
            c.drawRect(m - 1.5f, t, m + 1.5f, b, p);
        }
        p.setColor(Color.WHITE);
        p.setTextSize(28f);
        c.drawText("PENUMBRA SAMPLE", l + 12f, t + 32f, p);
        p.setTextSize(22f);
        c.drawText("drag box onto floor/wall beside corner", l + 12f, b - 14f, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float nx = e.getX() / Math.max(1f, getWidth());
        float ny = e.getY() / Math.max(1f, getHeight());
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            lastX = nx; lastY = ny; return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = nx - lastX, dy = ny - lastY;
            float w = roi.width(), h = roi.height();
            float nl = clamp(roi.left + dx, 0.01f, 0.99f - w);
            float nt = clamp(roi.top + dy, 0.01f, 0.99f - h);
            roi.set(nl, nt, nl + w, nt + h);
            lastX = nx; lastY = ny;
            invalidate();
            return true;
        }
        return true;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
