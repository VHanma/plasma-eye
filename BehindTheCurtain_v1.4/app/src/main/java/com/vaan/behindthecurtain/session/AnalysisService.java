package com.vaan.behindthecurtain.session;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

public class AnalysisService extends Service {
    public static final String ACTION_ANALYZE = "btc.session.ANALYZE";
    public static final String EXTRA_SESSION_PATH = "session_path";
    private static final String CHANNEL = "btc_analysis";
    private static final int NOTIFICATION_ID = 1402;
    private volatile boolean working;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CHANNEL, "Behind the Curtain analysis", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Post-session visual, subtitle and reverse-audio analysis");
        nm.createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_ANALYZE.equals(intent.getAction()) || working) return START_NOT_STICKY;
        String path = intent.getStringExtra(EXTRA_SESSION_PATH);
        if (path == null) { stopSelf(); return START_NOT_STICKY; }
        working = true;
        startAnalysisForeground();
        new Thread(() -> {
            try { runAnalysis(new File(path)); }
            catch (Throwable t) { SessionStore.appendText(new File(path, "analysis_errors.txt"), "fatal: " + t + "\n"); }
            finally {
                working = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "btc-analysis").start();
        return START_NOT_STICKY;
    }

    private void startAnalysisForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Behind the Curtain analyzing")
                .setContentText("Building traces, subtitles and reverse-audio files")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void runAnalysis(File sessionDir) throws Exception {
        SessionStore store = SessionStore.open(sessionDir);
        SessionStore.appendText(new File(store.session, "session_info.txt"), "Post analysis started: " + new Date() + "\n");

        File original = new File(store.audio, "original.wav");
        File reversed = new File(store.reversedAudio, "full_reversed_play_forward.wav");
        if (original.exists() && original.length() > 44) {
            WavFile.reverseMono16(original, reversed);
            SessionStore.writeText(new File(store.reversedAudio, "README.txt"),
                    "full_reversed_play_forward.wav is the complete captured session reversed sample-by-sample.\n" +
                    "Play it normally to hear the captured audio in reverse direction.\n");
            SessionStore.writeText(new File(store.audio, "analysis_notes.txt"),
                    "Capture format: 48 kHz mono PCM16 WAV.\n" +
                    "audio_events.csv flags quiet-level, very-low-frequency and high-frequency energy candidates.\n" +
                    "48 kHz digital audio represents frequencies below 24 kHz; the actual capture path can have a narrower response.\n");

            SpeechFileTranscriber tx = new SpeechFileTranscriber(this);
            tx.transcribe(original, new File(store.audio, "subtitles.srt"), new File(store.audio, "transcript.txt"), "FORWARD");
            tx.transcribe(reversed, new File(store.reversedAudio, "subtitles.srt"), new File(store.reversedAudio, "transcript.txt"), "REVERSED");
        }

        new VisualPostAnalyzer().run(store);
        writeIndex(store);
        SessionStore.writeText(new File(store.session, "ANALYSIS_COMPLETE.txt"),
                "Completed: " + new Date() + "\n" +
                "audio/ = original capture, event scan, forward subtitles\n" +
                "visual/ = originals, reveal images, traces, OCR notes\n" +
                "reversed_audio/ = full reversed audio and reverse subtitles\n");
    }

    private void writeIndex(SessionStore s) {
        StringBuilder out = new StringBuilder("BEHIND THE CURTAIN SESSION INDEX\nGenerated: " + new Date() + "\n\n");
        appendFolder(out, "audio", s.audio);
        appendFolder(out, "visual", s.visual);
        appendFolder(out, "reversed_audio", s.reversedAudio);
        SessionStore.writeText(new File(s.session, "INDEX.txt"), out.toString());
    }

    private void appendFolder(StringBuilder out, String label, File dir) {
        out.append('[').append(label).append("]\n");
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) out.append("  ").append(f.getName()).append("  ").append(f.length()).append(" bytes\n");
        }
        out.append('\n');
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
