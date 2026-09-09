package com.omega.projector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_MEDIA = 601;

    private FrameLayout root;
    private OmegaGLView renderView;
    private LinearLayout topBar;
    private HorizontalScrollView controlScroll;
    private Button wallBtn, lensBtn, holoBtn, ghostBtn, mirrorBtn, audioBtn;
    private Uri selectedUri;
    private boolean selectedIsVideo = false;
    private boolean controlsHidden = false;
    private OmegaPresentation presentation;

    private SeekBar brightnessBar, contrastBar, gammaBar, glowBar, keyBar;
    private SeekBar topBarWarp, bottomBarWarp, skewBar, zoomBar, holoSizeBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        enterImmersive();
        buildUi();
        showWelcome();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        renderView = new OmegaGLView(this);
        root.addView(renderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderView.setOnClickListener(v -> setControlsHidden(!controlsHidden));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(Color.argb(190, 5, 8, 13));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP);
        root.addView(topBar, tp);

        TextView brand = new TextView(this);
        brand.setText("Ω PROJECTOR");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(17);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(136), ViewGroup.LayoutParams.MATCH_PARENT);
        topBar.addView(brand, bp);

        topBar.addView(button("LOAD", v -> pickMedia()));
        wallBtn = button("WALL", v -> setMode(OmegaGLView.MODE_WALL));
        lensBtn = button("LENS", v -> setMode(OmegaGLView.MODE_LENS));
        holoBtn = button("HOLO 4X", v -> setMode(OmegaGLView.MODE_HOLO4));
        ghostBtn = button("GHOST", v -> setMode(OmegaGLView.MODE_GHOST));
        topBar.addView(wallBtn);
        topBar.addView(lensBtn);
        topBar.addView(holoBtn);
        topBar.addView(ghostBtn);
        topBar.addView(button("CAST", v -> openCastSettings()));
        topBar.addView(button("DISPLAY", v -> choosePresentationDisplay()));
        topBar.addView(button("INFO", v -> showHelp()));
        topBar.addView(button("FULL", v -> setControlsHidden(true)));

        controlScroll = new HorizontalScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.setHorizontalScrollBarEnabled(false);
        controlScroll.setBackgroundColor(Color.argb(205, 5, 8, 13));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112), Gravity.BOTTOM);
        root.addView(controlScroll, cp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(4));
        controlScroll.addView(controls, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        brightnessBar = addSlider(controls, "BRIGHT", 175, 75, p -> renderView.setBrightness(0.25f + p / 100f));
        contrastBar = addSlider(controls, "CONTRAST", 150, 50, p -> renderView.setContrast(0.50f + p / 100f));
        gammaBar = addSlider(controls, "GAMMA", 150, 50, p -> renderView.setGamma(0.50f + p / 100f));
        glowBar = addSlider(controls, "GLOW", 200, 15, p -> renderView.setGlow(p / 100f));
        keyBar = addSlider(controls, "BG KEY", 80, 8, p -> renderView.setKeyThreshold(p / 100f));

        topBarWarp = addSlider(controls, "TOP KEY", 42, 0, p -> renderView.setTopTaper(p / 100f));
        bottomBarWarp = addSlider(controls, "BOTTOM KEY", 42, 0, p -> renderView.setBottomTaper(p / 100f));
        skewBar = addSlider(controls, "SKEW", 100, 50, p -> renderView.setSkew((p - 50) / 150f));
        zoomBar = addSlider(controls, "ZOOM", 100, 50, p -> renderView.setZoom(0.65f + p * 0.007f));
        holoSizeBar = addSlider(controls, "HOLO SIZE", 100, 74, p -> renderView.setHoloSize(0.45f + p * 0.005f));

        LinearLayout switches = new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        switches.setGravity(Gravity.CENTER);
        switches.setPadding(dp(6), 0, dp(6), 0);
        mirrorBtn = button("MIRROR OFF", v -> toggleMirror());
        audioBtn = button("AUDIO ON", v -> toggleAudio());
        Button playBtn = button("PLAY / PAUSE", v -> renderView.togglePlay());
        Button resetBtn = button("RESET", v -> resetControls());
        switches.addView(mirrorBtn);
        switches.addView(audioBtn);
        switches.addView(playBtn);
        switches.addView(resetBtn);
        controls.addView(switches, new LinearLayout.LayoutParams(dp(138), ViewGroup.LayoutParams.MATCH_PARENT));

        setMode(OmegaGLView.MODE_WALL);
        resetControls();
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(buttonBackground(false));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(82), dp(42));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable buttonBackground(boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(active ? Color.rgb(29, 107, 132) : Color.rgb(28, 34, 43));
        g.setStroke(dp(1), active ? Color.rgb(86, 230, 255) : Color.rgb(65, 76, 89));
        return g;
    }

    private interface SliderChange { void onValue(int progress); }

    private SeekBar addSlider(LinearLayout parent, String name, int max, int progress, SliderChange change) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(4), 0, dp(4), 0);
        TextView label = new TextView(this);
        label.setText(name + "  " + progress);
        label.setTextColor(Color.LTGRAY);
        label.setTextSize(10);
        label.setGravity(Gravity.CENTER);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(ColorStateList.valueOf(Color.rgb(70, 210, 235)));
            bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                label.setText(name + "  " + p);
                change.onValue(p);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(label, new LinearLayout.LayoutParams(dp(126), dp(28)));
        box.addView(bar, new LinearLayout.LayoutParams(dp(126), dp(58)));
        parent.addView(box, new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT));
        change.onValue(progress);
        return bar;
    }

    private void setMode(int mode) {
        renderView.setMode(mode);
        wallBtn.setBackground(buttonBackground(mode == OmegaGLView.MODE_WALL));
        lensBtn.setBackground(buttonBackground(mode == OmegaGLView.MODE_LENS));
        holoBtn.setBackground(buttonBackground(mode == OmegaGLView.MODE_HOLO4));
        ghostBtn.setBackground(buttonBackground(mode == OmegaGLView.MODE_GHOST));
        if (mode == OmegaGLView.MODE_LENS) toast("Lens mode rotates output 180° for a convex-lens box projector.");
        if (mode == OmegaGLView.MODE_HOLO4) toast("Holo 4X: center a transparent inverted pyramid over the screen.");
        if (mode == OmegaGLView.MODE_GHOST) toast("Ghost Glass: reflect the screen from clear glass/acrylic at about 45°.");
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_MEDIA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MEDIA || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        selectedUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        String type = getContentResolver().getType(selectedUri);
        selectedIsVideo = type != null && type.toLowerCase(Locale.US).startsWith("video/");
        if (type == null) {
            String u = selectedUri.toString().toLowerCase(Locale.US);
            selectedIsVideo = u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".webm") || u.endsWith(".3gp") || u.endsWith(".mov");
        }
        renderView.setMedia(selectedUri, selectedIsVideo);
        toast(selectedIsVideo ? "Video loaded into projection engine." : "Image loaded into projection engine.");
    }

    private void toggleMirror() {
        Object tag = mirrorBtn.getTag();
        boolean mirror = !(tag instanceof Boolean && (Boolean) tag);
        mirrorBtn.setTag(mirror);
        mirrorBtn.setText(mirror ? "MIRROR ON" : "MIRROR OFF");
        renderView.setMirror(mirror);
    }

    private void toggleAudio() {
        boolean muted = !renderView.isMuted();
        renderView.setMuted(muted);
        audioBtn.setText(muted ? "AUDIO OFF" : "AUDIO ON");
    }

    private void resetControls() {
        brightnessBar.setProgress(75);
        contrastBar.setProgress(50);
        gammaBar.setProgress(50);
        glowBar.setProgress(15);
        keyBar.setProgress(8);
        topBarWarp.setProgress(0);
        bottomBarWarp.setProgress(0);
        skewBar.setProgress(50);
        zoomBar.setProgress(50);
        holoSizeBar.setProgress(74);
        mirrorBtn.setTag(false);
        mirrorBtn.setText("MIRROR OFF");
        renderView.setMirror(false);
        renderView.setMuted(false);
        audioBtn.setText("AUDIO ON");
    }

    private void openCastSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_CAST_SETTINGS);
            startActivity(i);
        } catch (Exception first) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Exception ignored) { toast("Open your phone's screen mirroring / Smart View menu."); }
        }
    }

    private void choosePresentationDisplay() {
        if (selectedUri == null) {
            toast("Load an image or video first.");
            return;
        }
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            toast("No presentation display is active. Connect Cast/Mirror, HDMI, DeX or a wireless display first.");
            openCastSettings();
            return;
        }
        if (displays.length == 1) {
            showOnDisplay(displays[0]);
            return;
        }
        String[] names = new String[displays.length];
        for (int x = 0; x < displays.length; x++) names[x] = displays[x].getName();
        new AlertDialog.Builder(this)
                .setTitle("Project to display")
                .setItems(names, (d, which) -> showOnDisplay(displays[which]))
                .show();
    }

    private void showOnDisplay(Display display) {
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
        }
        renderView.pauseMedia();
        presentation = new OmegaPresentation(this, display, selectedUri, selectedIsVideo, renderView.snapshot());
        presentation.setOnDismissListener(d -> {
            presentation = null;
            renderView.resumeMedia();
        });
        try {
            presentation.show();
            toast("Projection routed to " + display.getName());
        } catch (Exception e) {
            presentation = null;
            renderView.resumeMedia();
            toast("That display rejected presentation mode. Use CAST screen mirroring instead.");
        }
    }

    private void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        int vis = hidden ? View.GONE : View.VISIBLE;
        topBar.setVisibility(vis);
        controlScroll.setVisibility(vis);
        enterImmersive();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showWelcome() {
        root.postDelayed(() -> new AlertDialog.Builder(this)
                .setTitle("Omega Projector")
                .setMessage("Load any image or video. WALL is optimized for Cast/HDMI/wireless displays. LENS is for a dark-box + magnifying-lens phone projector. HOLO 4X is for a transparent pyramid. GHOST is for one clear reflector at ~45°. Tap the picture to hide or reveal controls.")
                .setPositiveButton("ENTER", null)
                .show(), 250);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Projection modes")
                .setMessage(
                        "WALL\nCast/mirror the whole screen or use DISPLAY when Android exposes a presentation display. TOP KEY, BOTTOM KEY and SKEW correct wall geometry.\n\n" +
                        "LENS\nTurns the phone display into an optical image source and rotates it for the inversion produced by a convex magnifying lens. Best inside a dark box.\n\n" +
                        "HOLO 4X\nFour synchronized reflected views for a clear inverted pyramid. BG KEY suppresses dark background pixels; GLOW makes bright edges punch harder.\n\n" +
                        "GHOST\nSingle Pepper's-Ghost view for clear acrylic/glass around 45°.\n\n" +
                        "FULL\nHides every control. Tap the projection to bring them back.")
                .setPositiveButton("DONE", null)
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        renderView.onResume();
        enterImmersive();
    }

    @Override protected void onPause() {
        renderView.pauseMedia();
        renderView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
        }
        renderView.releaseMedia();
        super.onDestroy();
    }
}
