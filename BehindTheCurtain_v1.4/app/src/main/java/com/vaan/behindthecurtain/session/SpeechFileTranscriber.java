package com.vaan.behindthecurtain.session;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SpeechFileTranscriber {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public SpeechFileTranscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    public void transcribe(File wav, File srt, File transcript, String label) {
        if (Build.VERSION.SDK_INT < 33) {
            SessionStore.writeText(srt, "");
            SessionStore.writeText(transcript, label + " transcript unavailable: audio-source injection requires Android 13+.\n");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            SessionStore.writeText(srt, "");
            SessionStore.writeText(transcript, label + " transcript unavailable: speech recognition service not present.\n");
            return;
        }

        StringBuilder srtOut = new StringBuilder();
        StringBuilder textOut = new StringBuilder(label + " TRANSCRIPT\nSource: " + wav.getName() + "\n\n");
        int subtitleIndex = 1;

        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            int sampleRate = WavFile.sampleRate(wav);
            raf.seek(44);
            int secondsPerChunk = 10;
            int chunkBytes = sampleRate * 2 * secondsPerChunk;
            byte[] buffer = new byte[chunkBytes];
            long totalBytes = Math.max(0, raf.length() - 44);
            long consumed = 0;

            while (consumed < totalBytes) {
                int want = (int) Math.min(buffer.length, totalBytes - consumed);
                int got = raf.read(buffer, 0, want);
                if (got <= 0) break;
                if ((got & 1) != 0) got--;
                byte[] pcm = Arrays.copyOf(buffer, got);
                double startSec = consumed / (sampleRate * 2.0);
                double endSec = (consumed + got) / (sampleRate * 2.0);
                String words = recognizePcm(pcm, sampleRate);
                if (words != null && !words.trim().isEmpty()) {
                    String clean = words.trim().replace('\r', ' ').replace('\n', ' ');
                    srtOut.append(subtitleIndex++).append('\n')
                            .append(srtTime(startSec)).append(" --> ").append(srtTime(endSec)).append('\n')
                            .append(clean).append("\n\n");
                    textOut.append('[').append(srtTime(startSec)).append(" - ").append(srtTime(endSec)).append("] ")
                            .append(clean).append('\n');
                }
                consumed += got;
            }
        } catch (Throwable t) {
            textOut.append("\nTranscription error: ").append(t).append('\n');
        }

        SessionStore.writeText(srt, srtOut.toString());
        SessionStore.writeText(transcript, textOut.toString());
    }

    private String recognizePcm(byte[] pcm, int sampleRate) throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> resultText = new AtomicReference<>("");
        AtomicReference<SpeechRecognizer> recognizerRef = new AtomicReference<>();
        AtomicBoolean feedCompleted = new AtomicBoolean(false);

        main.post(() -> {
            try {
                SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(context);
                recognizerRef.set(sr);
                sr.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onError(int error) { done.countDown(); }
                    @Override public void onResults(Bundle results) {
                        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) resultText.set(list.get(0));
                        done.countDown();
                    }
                    @Override public void onPartialResults(Bundle partialResults) {}
                    @Override public void onEvent(int eventType, Bundle params) {}
                });

                Intent rec = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                rec.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                rec.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
                rec.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                rec.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                rec.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
                rec.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                rec.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                rec.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate);
                sr.startListening(rec);
            } catch (Throwable ignored) {
                done.countDown();
            } finally {
                started.countDown();
            }
        });

        if (!started.await(4, TimeUnit.SECONDS)) {
            close(pipe[0]);
            close(pipe[1]);
            return "";
        }

        Thread feeder = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                int p = 0;
                while (p < pcm.length) {
                    int n = Math.min(16384, pcm.length - p);
                    out.write(pcm, p, n);
                    p += n;
                }
                out.flush();
                feedCompleted.set(true);
            } catch (Throwable ignored) {}
        }, "btc-asr-feed");
        feeder.start();

        done.await(28, TimeUnit.SECONDS);
        try { feeder.join(1500); } catch (InterruptedException ignored) {}
        close(pipe[0]);
        SpeechRecognizer sr = recognizerRef.get();
        if (sr != null) main.post(() -> {
            try { sr.cancel(); } catch (Throwable ignored) {}
            try { sr.destroy(); } catch (Throwable ignored) {}
        });
        if (!feedCompleted.get()) return "";
        return resultText.get();
    }

    private static void close(ParcelFileDescriptor pfd) {
        if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
    }

    private static String srtTime(double sec) {
        long ms = Math.max(0, Math.round(sec * 1000.0));
        long h = ms / 3600000; ms %= 3600000;
        long m = ms / 60000; ms %= 60000;
        long s = ms / 1000; ms %= 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}
