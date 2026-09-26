package com.vaan.behindthecurtain.session;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SessionStore {
    public final File session;
    public final File audio;
    public final File visual;
    public final File reversedAudio;

    private SessionStore(File session) {
        this.session = session;
        this.audio = new File(session, "audio");
        this.visual = new File(session, "visual");
        this.reversedAudio = new File(session, "reversed_audio");
        audio.mkdirs();
        visual.mkdirs();
        reversedAudio.mkdirs();
    }

    public static File root(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) base = context.getFilesDir();
        File root = new File(base, "BehindTheCurtain/Sessions");
        root.mkdirs();
        return root;
    }

    public static SessionStore create(Context context) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File dir = new File(root(context), "session_" + stamp);
        int n = 1;
        while (dir.exists()) dir = new File(root(context), "session_" + stamp + "_" + (n++));
        dir.mkdirs();
        SessionStore s = new SessionStore(dir);
        writeText(new File(dir, "session_info.txt"),
                "Behind the Curtain v1.4 Session Engine\n" +
                "Started: " + new Date() + "\n" +
                "Folders:\n" +
                "  audio/           original capture, spectrum events, forward subtitles\n" +
                "  visual/          candidate frames, reveal images, traces, notes\n" +
                "  reversed_audio/  full reversed audio and reverse subtitles\n");
        return s;
    }

    public static SessionStore open(File dir) {
        return new SessionStore(dir);
    }

    public static void writeText(File file, String text) {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static void appendText(File file, String text) {
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
