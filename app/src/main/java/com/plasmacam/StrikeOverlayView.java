package com.plasmacam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class StrikeOverlayView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint perfectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float speed = 0, power = 0, technique = 0;
    private float pbSpeed = 0, pbPower = 0;
    private boolean isPerfect = false;
    private boolean showPerfect = false;
    private long perfectFlashEnd = 0;
    private int totalStrikes = 0;
    private String statusLine = "";

    public StrikeOverlayView(Context ctx) { super(ctx); init(); }
    public StrikeOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(38f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        barBg.setColor(0x66000000);
        barFill.setColor(0xFF00FF88);

        pbPaint.setColor(0xFFFFD700);
        pbPaint.setTextSize(32f);
        pbPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        perfectPaint.setColor(0xFFFF4400);
        perfectPaint.setTextSize(64f);
        perfectPaint.setFakeBoldText(true);
        perfectPaint.setShadowLayer(8f, 4f, 4f, Color.BLACK);
    }

    public void updateStrike(StrikeTracker.StrikeResult r, StrikeTracker.StrikeResult pbSpeedResult,
                              StrikeTracker.StrikeResult pbPowerResult, int total) {
        if (r == null) return;
        this.speed = r.speedMs;
        this.power = r.powerScore;
        this.technique = r.techniqueScore;
        this.isPerfect = r.isPerfect;
        this.totalStrikes = total;
        if (pbSpeedResult != null) this.pbSpeed = pbSpeedResult.speedMs;
        if (pbPowerResult != null) this.pbPower = pbPowerResult.powerScore;
        if (r.isPerfect) {
            perfectFlashEnd = System.currentTimeMillis() + 1500;
        }
        postInvalidate();
    }

    public void setStatusLine(String s) {
        this.statusLine = s;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float pad = 24f, barH = 22f, barW = 220f;
        float x = pad, y = 80f;

        // Speed
        canvas.drawText(String.format("SPEED  %.1f m/s", speed), x, y, textPaint);
        y += 10f;
        drawBar(canvas, x, y, barW, barH, Math.min(speed / 15f, 1f), 0xFF00AAFF);
        y += barH + 18f;

        // Power
        canvas.drawText(String.format("POWER  %.0f / 100", power), x, y, textPaint);
        y += 10f;
        drawBar(canvas, x, y, barW, barH, power / 100f, 0xFFFF4400);
        y += barH + 18f;

        // Technique
        canvas.drawText(String.format("TECH   %.0f / 100", technique), x, y, textPaint);
        y += 10f;
        drawBar(canvas, x, y, barW, barH, technique / 100f, 0xFF00FF88);
        y += barH + 24f;

        // Strikes count
        canvas.drawText("STRIKES: " + totalStrikes, x, y, textPaint);
        y += 40f;

        // Personal bests
        canvas.drawText(String.format("PB SPD  %.1f m/s", pbSpeed), x, y, pbPaint);
        y += 36f;
        canvas.drawText(String.format("PB PWR  %.0f", pbPower), x, y, pbPaint);

        // Status line bottom
        if (statusLine != null && !statusLine.isEmpty()) {
            textPaint.setTextSize(30f);
            canvas.drawText(statusLine, pad, h - 40f, textPaint);
            textPaint.setTextSize(38f);
        }

        // Perfect flash
        if (System.currentTimeMillis() < perfectFlashEnd) {
            String msg = "★ PERFECT STRIKE ★";
            float tw = perfectPaint.measureText(msg);
            canvas.drawText(msg, (w - tw) / 2f, h / 2f, perfectPaint);
            postInvalidateDelayed(50);
        }
    }

    private void drawBar(Canvas canvas, float x, float y, float w, float h, float fill, int color) {
        RectF bg = new RectF(x, y, x + w, y + h);
        canvas.drawRoundRect(bg, 6f, 6f, barBg);
        barFill.setColor(color);
        RectF fg = new RectF(x, y, x + w * fill, y + h);
        canvas.drawRoundRect(fg, 6f, 6f, barFill);
    }
}
