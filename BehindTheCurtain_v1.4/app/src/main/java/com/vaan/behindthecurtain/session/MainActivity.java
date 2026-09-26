package com.vaan.behindthecurtain.session;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 41;
    private static final int REQ_PERMS = 42;

    private TextView status;
    private TextView pathView;
    private ArrayAdapter<String> sessionsAdapter;
    private final List<File> sessionFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshSessions();
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        TextView title = text("BEHIND THE CURTAIN", 26, Color.rgb(230, 255, 249));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWrap());

        TextView sub = text("SESSION ENGINE v1.4", 13, Color.rgb(102, 255, 217));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub, fullWrap());

        TextView explainer = text(
                "Capture stays light. Heavy visual tracing, OCR, subtitles and reverse-audio transcription run after the session stops.",
                14, Color.rgb(185, 198, 208));
        explainer.setPadding(0, dp(12), 0, dp(12));
        root.addView(explainer, fullWrap());

        status = text("Ready", 16, Color.WHITE);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status, fullWrap());

        Button start = button("START SESSION");
        start.setOnClickListener(v -> beginCaptureFlow());
        root.addView(start, fullWrap());

        Button stop = button("STOP + ANALYZE");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
            status.setText("Stopping capture and starting organized analysis...");
        });
        root.addView(stop, fullWrap());

        Button refresh = button("REFRESH SESSIONS");
        refresh.setOnClickListener(v -> refreshSessions());
        root.addView(refresh, fullWrap());

        pathView = text("", 12, Color.rgb(155, 170, 180));
        pathView.setPadding(0, dp(10), 0, dp(6));
        root.addView(pathView, fullWrap());

        TextView header = text("SESSIONS", 15, Color.rgb(102, 255, 217));
        header.setPadding(0, dp(8), 0, dp(6));
        root.addView(header, fullWrap());

        ListView list = new ListView(this);
        list.setDividerHeight(1);
        sessionsAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.rgb(225, 234, 240));
                tv.setTextSize(14);
                tv.setPadding(dp(10), dp(10), dp(10), dp(10));
                return tv;
            }
        };
        list.setAdapter(sessionsAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> showSession(sessionFiles.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void beginCaptureFlow() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
            return;
        }
        launchProjectionConsent();
    }

    private void launchProjectionConsent() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        status.setText("Waiting for Android screen-capture permission...");
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int r : grantResults) ok &= r == PackageManager.PERMISSION_GRANTED;
            if (ok) launchProjectionConsent();
            else status.setText("Audio permission is needed for session audio capture.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Screen capture was not started.");
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        status.setText("SESSION LIVE  |  screen + audio collector active");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessions();
    }

    private void refreshSessions() {
        if (sessionsAdapter == null) return;
        File root = SessionStore.root(this);
        pathView.setText("Saved under: " + root.getAbsolutePath());
        sessionFiles.clear();
        File[] files = root.listFiles(File::isDirectory);
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            sessionFiles.addAll(Arrays.asList(files));
        }
        List<String> names = new ArrayList<>();
        for (File f : sessionFiles) {
            File done = new File(f, "ANALYSIS_COMPLETE.txt");
            names.add((done.exists() ? "✓ " : "… ") + f.getName());
        }
        sessionsAdapter.clear();
        sessionsAdapter.addAll(names);
        sessionsAdapter.notifyDataSetChanged();
    }

    private void showSession(File dir) {
        int visual = countFiles(new File(dir, "visual"));
        int audio = countFiles(new File(dir, "audio"));
        int reverse = countFiles(new File(dir, "reversed_audio"));
        String msg = "Visual files: " + visual +
                "\nAudio files: " + audio +
                "\nReversed-audio files: " + reverse +
                "\n\n" + dir.getAbsolutePath();
        new AlertDialog.Builder(this)
                .setTitle(dir.getName())
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private int countFiles(File dir) {
        File[] fs = dir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.rgb(230, 255, 249));
        b.setBackgroundColor(Color.rgb(20, 45, 45));
        LinearLayout.LayoutParams lp = fullWrap();
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
