package com.vaan.behindthecurtain.session;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class VisualPostAnalyzer {
    public void run(SessionStore store) {
        File[] originals = store.visual.listFiles((dir, name) -> name.endsWith("_original.png"));
        if (originals == null || originals.length == 0) {
            SessionStore.writeText(new File(store.visual, "visual_index.csv"), "event,ocr_original,ocr_reveal,description\n");
            return;
        }
        Arrays.sort(originals, Comparator.comparing(File::getName));
        StringBuilder index = new StringBuilder("event,ocr_original,ocr_reveal,description\n");
        TextRecognizer recognizer = null;
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            for (File original : originals) {
                String base = original.getName().replace("_original.png", "");
                File reveal = new File(store.visual, base + "_reveal.png");
                File note = new File(store.visual, base + "_note.txt");
                String ocrOriginal = ocr(recognizer, original);
                String ocrReveal = reveal.exists() ? ocr(recognizer, reveal) : "";
                String description;
                if (!ocrOriginal.isBlank() || !ocrReveal.isBlank()) {
                    description = "text-like content detected; trace and reveal are saved with the source frame";
                } else {
                    description = "faint edge or geometry candidate; no confident OCR text";
                }
                SessionStore.appendText(note,
                        "\nPOST_ANALYSIS\n" +
                        "description=" + description + "\n" +
                        "ocr_original=" + oneLine(ocrOriginal) + "\n" +
                        "ocr_reveal=" + oneLine(ocrReveal) + "\n");
                index.append(csv(base)).append(',')
                        .append(csv(oneLine(ocrOriginal))).append(',')
                        .append(csv(oneLine(ocrReveal))).append(',')
                        .append(csv(description)).append('\n');
            }
        } catch (Throwable t) {
            SessionStore.appendText(new File(store.session, "analysis_errors.txt"), "OCR: " + t + "\n");
        } finally {
            if (recognizer != null) try { recognizer.close(); } catch (Throwable ignored) {}
        }
        SessionStore.writeText(new File(store.visual, "visual_index.csv"), index.toString());
    }

    private String ocr(TextRecognizer recognizer, File file) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) return "";
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS);
            return result == null ? "" : result.getText();
        } catch (Throwable t) {
            return "";
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
