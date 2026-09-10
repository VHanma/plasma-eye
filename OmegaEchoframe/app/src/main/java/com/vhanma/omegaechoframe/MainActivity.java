package com.vhanma.omegaechoframe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity implements AcousticEngine.Listener {
    private static final int REQ_AUDIO = 7001;
    private AcousticEngine engine;
    private EchoRadarView radar;
    private TextView stateText;
    private TextView telemetryText;
    private Button startButton;
    private Button modeButton;
    private Button bandButton;
    private boolean pendingCalibration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        engine = new AcousticEngine(this, this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(0, 2, 6));

        TextView title = text("Ω ECHOFRAME", 26, Color.rgb(210, 245, 255));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = text("ACTIVE ACOUSTIC RANGE / MOTION / PHASE INSTRUMENT", 12, Color.rgb(80, 180, 205));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stateText = text("STANDBY • press START", 14, Color.rgb(255, 205, 90));
        stateText.setPadding(0, dp(5), 0, dp(2));
        root.addView(stateText);

        telemetryText = text("RANGE --   V --   MICRO --   LINK --", 13, Color.rgb(160, 235, 210));
        telemetryText.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
        root.addView(telemetryText);

        radar = new EchoRadarView(this);
        LinearLayout.LayoutParams radarParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        radarParams.topMargin = dp(4);
        radarParams.bottomMargin = dp(6);
        root.addView(radar, radarParams);

        LinearLayout row1 = row();
        startButton = button("START");
        Button calButton = button("AUTO CAL");
        modeButton = button("MOTION");
        row1.addView(startButton, weighted());
        row1.addView(calButton, weighted());
        row1.addView(modeButton, weighted());
        root.addView(row1);

        LinearLayout row2 = row();
        bandButton = button("18–21 kHz");
        Button baseButton = button("REBASE");
        Button freezeButton = button("FREEZE");
        row2.addView(bandButton, weighted());
        row2.addView(baseButton, weighted());
        row2.addView(freezeButton, weighted());
        root.addView(row2);

        LinearLayout senseRow = row();
        TextView senseLabel = text("SENS", 12, Color.rgb(120, 180, 200));
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setProgress(45);
        senseRow.addView(senseLabel, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        senseRow.addView(sensitivity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView powerLabel = text("PULSE", 12, Color.rgb(120, 180, 200));
        SeekBar power = new SeekBar(this);
        power.setMax(100);
        power.setProgress(52);
        senseRow.addView(powerLabel, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        senseRow.addView(power, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(senseRow);

        TextView hint = text("ROOM = static echo shells   •   MOTION = clutter-subtracted changes   •   MICRO = phase displacement", 11, Color.rgb(100, 130, 145));
        hint.setPadding(0, dp(2), 0, 0);
        root.addView(hint);

        setContentView(root);

        startButton.setOnClickListener(v -> {
            if (engine.isRunning()) engine.stop();
            else requestAndStart(false);
        });
        calButton.setOnClickListener(v -> requestAndStart(true));
        modeButton.setOnClickListener(v -> cycleMode());
        bandButton.setOnClickListener(v -> {
            int next = (engine.getBandIndex() + 1) % 3;
            engine.setBandIndex(next);
            updateBandButton(next);
            radar.clearHistory();
        });
        baseButton.setOnClickListener(v -> {
            engine.resetBaseline();
            radar.clearHistory();
            stateText.setText("REBASE • hold phone still while room signature learns");
        });
        freezeButton.setOnClickListener(v -> {
            radar.toggleFreeze();
            freezeButton.setText(radar.isFrozen() ? "UNFREEZE" : "FREEZE");
        });
        sensitivity.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float value = 1.5f + p * 0.085f;
                engine.setSensitivity(value);
                radar.setGain(0.65f + p / 65f);
            }
        });
        power.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                engine.setOutputPower(0.04f + p * 0.0026f);
            }
        });
    }

    private void requestAndStart(boolean calibrate) {
        pendingCalibration = calibrate;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!engine.isRunning()) engine.start();
        if (calibrate) {
            radar.clearHistory();
            radar.postDelayed(() -> engine.autoCalibrate(), 280);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestAndStart(pendingCalibration);
        } else if (requestCode == REQ_AUDIO) {
            stateText.setText("MICROPHONE PERMISSION REQUIRED FOR ACTIVE SONAR");
        }
    }

    private void cycleMode() {
        int next = (radar.getMode() + 1) % 3;
        radar.setMode(next);
        if (next == EchoRadarView.MODE_ROOM) modeButton.setText("ROOM");
        else if (next == EchoRadarView.MODE_MICRO) modeButton.setText("MICRO");
        else modeButton.setText("MOTION");
    }

    private void updateBandButton(int index) {
        if (index == 0) bandButton.setText("18–21 kHz");
        else if (index == 1) bandButton.setText("17–20 kHz");
        else bandButton.setText("16–19 kHz");
    }

    @Override public void onFrame(AcousticEngine.Frame frame) {
        runOnUiThread(() -> {
            radar.push(frame);
            updateBandButton(frame.bandLow == 18000 ? 0 : (frame.bandLow == 17000 ? 1 : 2));
            String range = frame.rangeMeters > 0f ? String.format(Locale.US, "%.2f m", frame.rangeMeters) : "--";
            telemetryText.setText(String.format(Locale.US,
                    "RANGE %s   V %+4.2f m/s   MICRO %+5.2f mm\nLINK %3.0f%%   CONF %3.0f%%   BASE %d%%   %.1f–%.1f kHz",
                    range, frame.radialVelocity, frame.microMm, frame.link * 100f,
                    frame.confidence * 100f, frame.warmupPercent, frame.bandLow / 1000f, frame.bandHigh / 1000f));
            if (frame.calibrating) stateText.setText("AUTO CAL • measuring acoustic return strength");
            startButton.setText("STOP");
        });
    }

    @Override public void onState(String state) {
        runOnUiThread(() -> {
            stateText.setText(state);
            startButton.setText(engine.isRunning() ? "STOP" : "START");
        });
    }

    @Override public void onError(String error) {
        runOnUiThread(() -> {
            stateText.setText("ENGINE • " + error);
            startButton.setText("START");
        });
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.stop();
        super.onDestroy();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(2), 0, dp(2));
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(18, 45, 57)));
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
