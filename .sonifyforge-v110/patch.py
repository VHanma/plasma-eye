from pathlib import Path

root = Path('sonifyforge-build/sonifyforge')
main = root / 'app/src/main/java/com/vaanhanma/sonifyforge/MainActivity.java'
csv = root / 'app/src/main/java/com/vaanhanma/sonifyforge/CsvData.java'
build = root / 'app/build.gradle'
readme = root / 'README.md'

s = main.read_text()
s = s.replace('private static final int REQ_EXPORT_WAV = 1003;', 'private static final int REQ_EXPORT_WAV = 1003;\n    private static final int REQ_OPEN_IMAGE = 1004;')
s = s.replace('private String sourceName = "Built-in sample";', 'private String sourceName = "Built-in sample";\n    private String sourceKind = "demo";')
s = s.replace('loadData(CsvData.sample(), null, "Built-in sample");', 'loadData(CsvData.sample(), null, "Built-in sample", "demo");')
old = '''        LinearLayout sourceButtons = horizontal();
        Button open = button("Open CSV / TSV", ACCENT);
        Button sample = button("Load Demo", ACCENT2);
        Button about = button("About", PANEL2);
        sourceButtons.addView(open, weightParams());
        sourceButtons.addView(space(dp(8)));
        sourceButtons.addView(sample, weightParams());
        sourceButtons.addView(space(dp(8)));
        sourceButtons.addView(about, weightParams());
        dataCard.addView(sourceButtons);
'''
new = '''        LinearLayout sourceButtons = horizontal();
        Button openImage = button("Open Image", ACCENT);
        Button open = button("Open CSV / TSV", ACCENT2);
        sourceButtons.addView(openImage, weightParams());
        sourceButtons.addView(space(dp(8)));
        sourceButtons.addView(open, weightParams());
        dataCard.addView(sourceButtons);

        LinearLayout sourceButtons2 = horizontal();
        sourceButtons2.setPadding(0, dp(8), 0, 0);
        Button sample = button("Load Demo", PANEL2);
        Button about = button("About", PANEL2);
        sourceButtons2.addView(sample, weightParams());
        sourceButtons2.addView(space(dp(8)));
        sourceButtons2.addView(about, weightParams());
        dataCard.addView(sourceButtons2);
'''
assert old in s
s = s.replace(old, new)
s = s.replace('''        dataCard.addView(sourceText);
        dataCard.addView(statsText);
''', '''        dataCard.addView(sourceText);
        dataCard.addView(statsText);
        TextView imageHint = text("Images auto-create time, position, color, brightness, hue, saturation, edge and contrast data.", 12, MUTED, false);
        imageHint.setPadding(0, dp(8), 0, 0);
        dataCard.addView(imageHint);
''', 1)
s = s.replace('open.setOnClickListener(v -> openCsv());\n        sample.setOnClickListener(v -> loadData(CsvData.sample(), null, "Built-in sample"));', 'openImage.setOnClickListener(v -> openImage());\n        open.setOnClickListener(v -> openCsv());\n        sample.setOnClickListener(v -> loadData(CsvData.sample(), null, "Built-in sample", "demo"));')
s = s.replace('''    private void openCsv() {
''', '''    private void openImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN_IMAGE);
    }

    private void openCsv() {
''', 1)
old = '''        if (requestCode == REQ_OPEN_CSV) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String name = queryDisplayName(uri);
            setStatus("Reading " + name + "…", ACCENT2);
            new Thread(() -> {
                try {
                    CsvData parsed = CsvData.parse(readText(uri));
                    runOnUiThread(() -> loadData(parsed, uri, name));
                } catch (Throwable t) {
                    runOnUiThread(() -> showError("Could not read data", t));
                }
            }, "SonifyForgeCsvRead").start();
        } else if (requestCode == REQ_EXPORT_MIDI || requestCode == REQ_EXPORT_WAV) {
'''
new = '''        if (requestCode == REQ_OPEN_CSV) {
            persistReadPermission(uri);
            String name = queryDisplayName(uri);
            setStatus("Reading " + name + "…", ACCENT2);
            new Thread(() -> {
                try {
                    CsvData parsed = CsvData.parse(readText(uri));
                    runOnUiThread(() -> loadData(parsed, uri, name, "csv"));
                } catch (Throwable t) {
                    runOnUiThread(() -> showError("Could not read data", t));
                }
            }, "SonifyForgeCsvRead").start();
        } else if (requestCode == REQ_OPEN_IMAGE) {
            persistReadPermission(uri);
            String name = queryDisplayName(uri);
            setStatus("Extracting image features from " + name + "…", ACCENT2);
            new Thread(() -> {
                try {
                    ImageFeatureExtractor.Result result = ImageFeatureExtractor.extract(getContentResolver(), uri);
                    runOnUiThread(() -> {
                        loadData(result.data, uri, name, "image");
                        statsText.setText(result.describe());
                        setStatus("Image converted to numeric feature tracks. Press PLAY or change the mapping.", ACCENT);
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> showError("Could not read image", t));
                }
            }, "SonifyForgeImageRead").start();
        } else if (requestCode == REQ_EXPORT_MIDI || requestCode == REQ_EXPORT_WAV) {
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('private void loadData(CsvData newData, Uri uri, String name) {', 'private void loadData(CsvData newData, Uri uri, String name, String kind) {')
s = s.replace('''        sourceUri = uri;
        sourceName = name == null ? "Data source" : name;
''', '''        sourceUri = uri;
        sourceName = name == null ? "Data source" : name;
        sourceKind = kind == null ? "csv" : kind;
''', 1)
old = '''        int firstY = numericColumns.length > 1 ? numericColumns[1] : numericColumns[0];
        int melodicIndex = 0;
        for (int col : numericColumns) {
            boolean enabled = col == firstY;
            int instrument = melodicIndex == 0 ? 0 : melodicIndex == 1 ? 89 : melodicIndex == 2 ? 33 : (melodicIndex * 8) % 128;
            trackConfigs.add(new SonifyEngine.TrackConfig(col, enabled, instrument, 0.80f));
            melodicIndex++;
        }
'''
new = '''        int firstY = numericColumns.length > 1 ? numericColumns[1] : numericColumns[0];
        int melodicIndex = 0;
        for (int col : numericColumns) {
            String header = data.headers[col];
            boolean enabled;
            if ("image".equals(sourceKind)) {
                enabled = "brightness".equals(header) || "edge".equals(header) || "saturation".equals(header);
            } else {
                enabled = col == firstY;
            }
            int instrument;
            if ("brightness".equals(header)) instrument = 0;
            else if ("edge".equals(header)) instrument = 89;
            else if ("saturation".equals(header)) instrument = 33;
            else instrument = melodicIndex == 0 ? 0 : (melodicIndex * 8) % 128;
            trackConfigs.add(new SonifyEngine.TrackConfig(col, enabled, instrument, 0.80f));
            melodicIndex++;
        }
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('root.put("sourceUri", sourceUri == null ? "" : sourceUri.toString());', 'root.put("sourceUri", sourceUri == null ? "" : sourceUri.toString());\n        root.put("sourceKind", sourceKind);', 1)
old = '''                String uriString = root.optString("sourceUri", "");
                String savedSourceName = root.optString("sourceName", "Data source");
                Uri uri = uriString.isEmpty() ? null : Uri.parse(uriString);
                CsvData loaded = uri == null ? CsvData.sample() : CsvData.parse(readText(uri));
                runOnUiThread(() -> {
                    try {
                        loadData(loaded, uri, savedSourceName);
                        applyProjectSettings(root);
                        setStatus("Project loaded: " + root.optString("name", file.getName()), ACCENT);
                    } catch (Throwable t) { showError("Project settings failed", t); }
                });
'''
new = '''                String uriString = root.optString("sourceUri", "");
                String savedSourceName = root.optString("sourceName", "Data source");
                String savedKind = root.optString("sourceKind", uriString.isEmpty() ? "demo" : "csv");
                Uri uri = uriString.isEmpty() ? null : Uri.parse(uriString);
                CsvData loaded;
                String imageDescription = null;
                if (uri == null) {
                    loaded = CsvData.sample();
                    savedKind = "demo";
                } else if ("image".equals(savedKind)) {
                    ImageFeatureExtractor.Result image = ImageFeatureExtractor.extract(getContentResolver(), uri);
                    loaded = image.data;
                    imageDescription = image.describe();
                } else {
                    loaded = CsvData.parse(readText(uri));
                }
                final String finalKind = savedKind;
                final String finalImageDescription = imageDescription;
                runOnUiThread(() -> {
                    try {
                        loadData(loaded, uri, savedSourceName, finalKind);
                        if (finalImageDescription != null) statsText.setText(finalImageDescription);
                        applyProjectSettings(root);
                        setStatus("Project loaded: " + root.optString("name", file.getName()), ACCENT);
                    } catch (Throwable t) { showError("Project settings failed", t); }
                });
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('''    private String readText(Uri uri) throws Exception {
''', '''    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private String readText(Uri uri) throws Exception {
''', 1)
s = s.replace('Core abilities\\n• CSV / TSV numeric import\\n• multi-track mapping', 'Core abilities\\n• direct photo import with automatic image-feature extraction\\n• CSV / TSV numeric import\\n• multi-track mapping')
s = s.replace('• local project presets\\n\\n" +', '• local project presets\\n\\nImage mode automatically turns the full picture into numeric time, X/Y position, brightness, RGB, hue, saturation, edge, contrast and warmth channels.\\n\\n" +')
s = s.replace('loadData(CsvData.sample(), null, "Built-in sample")', 'loadData(CsvData.sample(), null, "Built-in sample", "demo")')
main.write_text(s)

c = csv.read_text()
needle = '    public static CsvData sample() {\n'
insert = '''    public static CsvData fromValues(String[] headers, double[][] values) {
        if (headers == null || values == null || headers.length < 2)
            throw new IllegalArgumentException("Need at least two columns");
        if (values.length == 0) throw new IllegalArgumentException("No data rows");
        String[] safeHeaders = Arrays.copyOf(headers, headers.length);
        double[][] safeValues = new double[values.length][headers.length];
        for (int r = 0; r < values.length; r++) {
            if (values[r] == null || values[r].length < headers.length)
                throw new IllegalArgumentException("Row " + r + " is missing columns");
            System.arraycopy(values[r], 0, safeValues[r], 0, headers.length);
        }
        return new CsvData(safeHeaders, safeValues);
    }

'''
assert needle in c
c = c.replace(needle, insert + needle, 1)
csv.write_text(c)

b = build.read_text().replace('versionCode 1', 'versionCode 2').replace("versionName '1.0.0'", "versionName '1.1.0'")
build.write_text(b)

if readme.exists():
    r = readme.read_text()
    r = r.replace('## What it does\n\n', '## What it does\n\n- Imports ordinary photos directly and automatically converts the full image into numeric spatial/color/edge feature tracks.\n')
    readme.write_text(r)
