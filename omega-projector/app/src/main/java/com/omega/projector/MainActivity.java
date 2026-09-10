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
    private ProjectionEngineView engine;
    private LinearLayout topBar;
    private HorizontalScrollView controlScroll;
    private TextView statusView;
    private Button wallBtn, lensBtn, holoBtn, ghostBtn, mirrorBtn, audioBtn;
    private Uri selectedUri;
    private boolean selectedIsVideo;
    private boolean controlsHidden;
    private OmegaPresentation presentation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);
        enterImmersive();
        buildUi();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        engine = new ProjectionEngineView(this);
        root.addView(engine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        engine.setOnClickListener(v -> setControlsHidden(!controlsHidden));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6),dp(4),dp(6),dp(4));
        topBar.setBackgroundColor(Color.argb(208,4,8,14));
        root.addView(topBar,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(58),Gravity.TOP));

        TextView brand = new TextView(this);
        brand.setText("Ω PROJECTOR v2");
        brand.setTextColor(Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(15);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(brand,new LinearLayout.LayoutParams(dp(142),ViewGroup.LayoutParams.MATCH_PARENT));

        topBar.addView(button("LOAD",v -> pickMedia()));
        topBar.addView(button("TEST",v -> { selectedUri=null; selectedIsVideo=false; engine.setTestPattern(); }));
        wallBtn = button("WALL",v -> setMode(ProjectionEngineView.MODE_WALL));
        lensBtn = button("LENS",v -> setMode(ProjectionEngineView.MODE_LENS));
        holoBtn = button("HOLO 4X",v -> setMode(ProjectionEngineView.MODE_HOLO4));
        ghostBtn = button("GHOST",v -> setMode(ProjectionEngineView.MODE_GHOST));
        topBar.addView(wallBtn); topBar.addView(lensBtn); topBar.addView(holoBtn); topBar.addView(ghostBtn);
        topBar.addView(button("PROJECT",v -> projectNow()));
        topBar.addView(button("FULL",v -> setControlsHidden(true)));
        topBar.addView(button("INFO",v -> showHelp()));

        statusView = new TextView(this);
        statusView.setText("TEST PATTERN VISIBLE");
        statusView.setTextColor(Color.rgb(120,235,255));
        statusView.setTextSize(11);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(Color.argb(205,3,8,13));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(30),Gravity.BOTTOM);
        sp.bottomMargin = dp(106);
        root.addView(statusView,sp);
        engine.setStatusListener(s -> runOnUiThread(() -> statusView.setText(s)));

        controlScroll = new HorizontalScrollView(this);
        controlScroll.setHorizontalScrollBarEnabled(false);
        controlScroll.setBackgroundColor(Color.argb(215,4,8,14));
        root.addView(controlScroll,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(106),Gravity.BOTTOM));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(6),dp(2),dp(6),dp(2));
        controlScroll.addView(controls,new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.MATCH_PARENT));

        addSlider(controls,"BRIGHT",150,75,p -> engine.setBrightness(0.25f+p/100f));
        addSlider(controls,"CONTRAST",150,50,p -> engine.setContrast(0.5f+p/100f));
        addSlider(controls,"TOP KEY",38,0,p -> engine.setTopTaper(p/100f));
        addSlider(controls,"BOTTOM KEY",38,0,p -> engine.setBottomTaper(p/100f));
        addSlider(controls,"SKEW",100,50,p -> engine.setSkew((p-50)/180f));
        addSlider(controls,"ZOOM",100,50,p -> engine.setZoom(0.65f+p*0.007f));
        addSlider(controls,"HOLO SIZE",100,74,p -> engine.setHoloSize(0.45f+p*0.006f));

        LinearLayout switches = new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        switches.setGravity(Gravity.CENTER);
        mirrorBtn = button("MIRROR OFF",v -> toggleMirror());
        audioBtn = button("AUDIO ON",v -> toggleAudio());
        switches.addView(mirrorBtn);
        switches.addView(audioBtn);
        switches.addView(button("PLAY/PAUSE",v -> engine.togglePlay()));
        controls.addView(switches,new LinearLayout.LayoutParams(dp(132),ViewGroup.LayoutParams.MATCH_PARENT));

        setMode(ProjectionEngineView.MODE_WALL);
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/*"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_MEDIA);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode!=PICK_MEDIA || resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        selectedUri=data.getData();
        try { getContentResolver().takePersistableUriPermission(selectedUri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
        String type=getContentResolver().getType(selectedUri);
        selectedIsVideo=type!=null && type.toLowerCase(Locale.US).startsWith("video/");
        if (type==null) {
            String u=selectedUri.toString().toLowerCase(Locale.US);
            selectedIsVideo=u.endsWith(".mp4")||u.endsWith(".mkv")||u.endsWith(".webm")||u.endsWith(".mov")||u.endsWith(".3gp")||u.endsWith(".m4v");
        }
        engine.setMedia(selectedUri,selectedIsVideo);
    }

    private void setMode(int mode) {
        engine.setMode(mode);
        wallBtn.setBackground(buttonBackground(mode==ProjectionEngineView.MODE_WALL));
        lensBtn.setBackground(buttonBackground(mode==ProjectionEngineView.MODE_LENS));
        holoBtn.setBackground(buttonBackground(mode==ProjectionEngineView.MODE_HOLO4));
        ghostBtn.setBackground(buttonBackground(mode==ProjectionEngineView.MODE_GHOST));
        if (mode==ProjectionEngineView.MODE_LENS) statusView.setText("LENS MODE • SCREEN SOURCE ROTATED FOR OPTICAL PROJECTION");
        if (mode==ProjectionEngineView.MODE_HOLO4) statusView.setText("HOLO 4X • PLACE CLEAR INVERTED PYRAMID AT CENTER");
        if (mode==ProjectionEngineView.MODE_GHOST) statusView.setText("GHOST • CLEAR REFLECTOR AROUND 45°");
    }

    private void projectNow() {
        DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
        Display[] displays=dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length>0) {
            if (displays.length==1) showOnDisplay(displays[0]);
            else {
                String[] names=new String[displays.length];
                for(int i=0;i<displays.length;i++) names[i]=displays[i].getName();
                new AlertDialog.Builder(this).setTitle("Choose projection display").setItems(names,(d,w)->showOnDisplay(displays[w])).show();
            }
            return;
        }
        statusView.setText("CONNECTING PATH • CHOOSE A WIRELESS DISPLAY, THEN TAP PROJECT AGAIN");
        try { startActivity(new Intent(Settings.ACTION_CAST_SETTINGS)); }
        catch(Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch(Throwable ignored) { toast("Open Smart View / Cast / Screen Mirroring, connect a display, then tap PROJECT."); }
        }
    }

    private void showOnDisplay(Display display) {
        if (presentation!=null) try { presentation.dismiss(); } catch(Throwable ignored) {}
        engine.pauseMedia();
        presentation=new OmegaPresentation(this,display,selectedUri,selectedIsVideo,engine.snapshot());
        presentation.setOnDismissListener(d -> { presentation=null; engine.resumeMedia(); });
        try { presentation.show(); statusView.setText("PROJECTING TO " + display.getName().toUpperCase(Locale.US)); }
        catch(Throwable t) { presentation=null; engine.resumeMedia(); statusView.setText("DISPLAY REJECTED PRESENTATION • USE SCREEN MIRROR"); }
    }

    private void toggleMirror() {
        boolean value=!(mirrorBtn.getTag() instanceof Boolean && (Boolean)mirrorBtn.getTag());
        mirrorBtn.setTag(value);
        mirrorBtn.setText(value?"MIRROR ON":"MIRROR OFF");
        engine.setMirror(value);
    }

    private void toggleAudio() {
        boolean muted=!engine.isMuted();
        engine.setMuted(muted);
        audioBtn.setText(muted?"AUDIO OFF":"AUDIO ON");
    }

    private Button button(String text,View.OnClickListener click) {
        Button b=new Button(this);
        b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(10); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false); b.setGravity(Gravity.CENTER); b.setPadding(dp(5),0,dp(5),0);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(buttonBackground(false)); b.setOnClickListener(click);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(82),dp(42)); lp.setMargins(dp(2),0,dp(2),0); b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable buttonBackground(boolean active) {
        GradientDrawable g=new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(active?Color.rgb(24,105,132):Color.rgb(28,34,43));
        g.setStroke(dp(1),active?Color.rgb(100,235,255):Color.rgb(65,76,89));
        return g;
    }

    private interface SliderChange { void onValue(int p); }
    private void addSlider(LinearLayout parent,String name,int max,int progress,SliderChange change) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView label=new TextView(this); label.setText(name+"  "+progress); label.setTextColor(Color.LTGRAY); label.setTextSize(10); label.setGravity(Gravity.CENTER);
        SeekBar bar=new SeekBar(this); bar.setMax(max); bar.setProgress(progress);
        if (android.os.Build.VERSION.SDK_INT>=21) { bar.setProgressTintList(ColorStateList.valueOf(Color.rgb(70,210,235))); bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE)); }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean f){ label.setText(name+"  "+p); change.onValue(p); }
            @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){}
        });
        box.addView(label,new LinearLayout.LayoutParams(dp(124),dp(27)));
        box.addView(bar,new LinearLayout.LayoutParams(dp(124),dp(56)));
        parent.addView(box,new LinearLayout.LayoutParams(dp(130),ViewGroup.LayoutParams.MATCH_PARENT));
        change.onValue(progress);
    }

    private void setControlsHidden(boolean hidden) {
        controlsHidden=hidden;
        int vis=hidden?View.GONE:View.VISIBLE;
        topBar.setVisibility(vis); controlScroll.setVisibility(vis); statusView.setVisibility(vis);
        enterImmersive();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Omega Projector v2")
                .setMessage("WALL uses Android's native video surface for maximum compatibility. PROJECT routes to an active external/presentation display, or opens your phone's wireless display screen when none is connected.\n\nLENS turns the phone display into the image source for a dark-box + convex-lens projector.\n\nHOLO 4X renders four synchronized views for a transparent inverted pyramid. GHOST renders one reflection image for clear acrylic/glass.\n\nTEST must always show color bars. The status strip reports OPENING VIDEO, VIDEO PLAYING, and VIDEO FRAME VISIBLE so black-video failures are diagnosable instead of silent.")
                .setPositiveButton("OK",null).show();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value){ return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        if (presentation!=null) try { presentation.dismiss(); } catch(Throwable ignored) {}
        if (engine!=null) engine.releaseMedia();
        super.onDestroy();
    }
}
