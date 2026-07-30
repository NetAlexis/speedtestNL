#!/usr/bin/env python3
from pathlib import Path
import re

service_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
gradle_path = Path("app/build.gradle")
workflow_path = Path(".github/workflows/build.yml")
service = service_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

service = replace_once(
    service,
    "import java.util.regex.Pattern;\n",
    "import java.util.regex.Pattern;\n"
    "import java.util.concurrent.ExecutorService;\n"
    "import java.util.concurrent.Executors;\n",
    "executor imports",
)

service = replace_once(
    service,
    "    private static final long START_CONFIRM_TIMEOUT_MS = 35 * 1000L;\n"
    "    private static final long START_DATA_TIMEOUT_MS = 150 * 1000L;\n"
    "    private static final long OCR_START_AFTER_MS = 30 * 1000L;\n"
    "    private static final long OCR_RETRY_INTERVAL_MS = 5000L;",
    "    private static final long START_CONFIRM_TIMEOUT_MS = 90 * 1000L;\n"
    "    private static final long START_DATA_TIMEOUT_MS = 150 * 1000L;\n"
    "    private static final long OCR_START_AFTER_MS = 18 * 1000L;\n"
    "    private static final long OCR_START_WHILE_UNCONFIRMED_MS = 12 * 1000L;\n"
    "    private static final long OCR_RETRY_INTERVAL_MS = 7000L;",
    "OCR timing",
)

service = replace_once(
    service,
    "    private static final long WATCHDOG_INTERVAL_MS = 1800L;\n"
    "    private static final int MAX_START_ATTEMPTS = 3;\n"
    "    private static final int MAX_OCR_ATTEMPTS = 12;",
    "    private static final long WATCHDOG_INTERVAL_MS = 1800L;\n"
    "    private static final long MIN_INSPECTION_INTERVAL_MS = 900L;\n"
    "    private static final int MAX_ACCESSIBILITY_TEXT_LINES = 500;\n"
    "    private static final int MAX_START_ATTEMPTS = 1;\n"
    "    private static final int MAX_OCR_ATTEMPTS = 10;",
    "inspection limits",
)

service = replace_once(
    service,
    "    private final Handler handler = new Handler(Looper.getMainLooper());\n",
    "    private final Handler handler = new Handler(Looper.getMainLooper());\n"
    "    private final ExecutorService ocrExecutor =\n"
    "        Executors.newSingleThreadExecutor(runnable -> {\n"
    "            Thread thread = new Thread(runnable, \"SpeedtestNL-nPerfOCR\");\n"
    "            thread.setPriority(Thread.NORM_PRIORITY - 1);\n"
    "            return thread;\n"
    "        });\n",
    "OCR executor field",
)

service = replace_once(
    service,
    "    private boolean terminalSent = false;\n"
    "    private boolean inspecting = false;\n"
    "    private boolean ocrInProgress = false;",
    "    private boolean terminalSent = false;\n"
    "    private boolean inspecting = false;\n"
    "    private boolean inspectionScheduled = false;\n"
    "    private long lastInspectionAt = 0L;\n"
    "    private boolean ocrInProgress = false;",
    "inspection state",
)

service = replace_once(
    service,
    "            inspectActiveWindow();\n"
    "            if (syncSession()) handler.postDelayed(this, WATCHDOG_INTERVAL_MS);",
    "            scheduleInspection(0L);\n"
    "            if (syncSession()) handler.postDelayed(this, WATCHDOG_INTERVAL_MS);",
    "watchdog scheduling",
)

service = replace_once(
    service,
    "        if (textRecognizer != null) {\n"
    "            textRecognizer.close();\n"
    "            textRecognizer = null;\n"
    "        }\n"
    "        super.onDestroy();",
    "        if (textRecognizer != null) {\n"
    "            textRecognizer.close();\n"
    "            textRecognizer = null;\n"
    "        }\n"
    "        ocrExecutor.shutdownNow();\n"
    "        super.onDestroy();",
    "executor shutdown",
)

service = replace_once(
    service,
    "        if (!isExpectedBrowser(eventPackage)) return;\n\n"
    "        inspectActiveWindow();",
    "        if (!isExpectedBrowser(eventPackage)) return;\n\n"
    "        scheduleInspection(120L);",
    "accessibility event debounce",
)

inspection_marker = "    private void inspectActiveWindow() {\n"
schedule_method = '''    private void scheduleInspection(long requestedDelayMs) {
        if (terminalSent || inspectionScheduled || !syncSession()) return;
        long now = SystemClock.elapsedRealtime();
        long throttle = Math.max(0L,
            MIN_INSPECTION_INTERVAL_MS - (now - lastInspectionAt));
        long delay = Math.max(requestedDelayMs, throttle);
        inspectionScheduled = true;
        handler.postDelayed(() -> {
            inspectionScheduled = false;
            lastInspectionAt = SystemClock.elapsedRealtime();
            inspectActiveWindow();
        }, delay);
    }

'''
service = replace_once(
    service,
    inspection_marker,
    schedule_method + inspection_marker,
    "inspection scheduler insertion",
)

service = replace_once(
    service,
    "            terminalSent = false;\n"
    "            inspecting = false;\n"
    "            ocrInProgress = false;",
    "            terminalSent = false;\n"
    "            inspecting = false;\n"
    "            inspectionScheduled = false;\n"
    "            lastInspectionAt = 0L;\n"
    "            ocrInProgress = false;",
    "new session inspection reset",
)

service = replace_once(
    service,
    "        terminalSent = false;\n"
    "        inspecting = false;\n"
    "        ocrInProgress = false;",
    "        terminalSent = false;\n"
    "        inspecting = false;\n"
    "        inspectionScheduled = false;\n"
    "        lastInspectionAt = 0L;\n"
    "        ocrInProgress = false;",
    "local session inspection reset",
)

# Start visual verification while Chrome keeps a stale Iniciar test node.
service = replace_once(
    service,
    "        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);\n"
    "        boolean newThroughput = hasValidThroughput(sanitized);",
    "        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);\n"
    "        boolean newThroughput = hasValidThroughput(sanitized);\n"
    "        long sinceFirstRequest = now - firstStartRequestAt;\n"
    "        if (sinceFirstRequest >= OCR_START_WHILE_UNCONFIRMED_MS) {\n"
    "            requestVisualResultOcr(false, now);\n"
    "        }",
    "unconfirmed OCR start",
)

service = replace_once(
    service,
    "        long sinceFirstRequest = now - firstStartRequestAt;\n"
    "        if (startVisible && startAttempts < MAX_START_ATTEMPTS &&",
    "        if (startVisible && startAttempts < MAX_START_ATTEMPTS &&",
    "duplicate sinceFirstRequest removal",
)

service = replace_once(
    service,
    "        if (sinceFirstRequest >= START_CONFIRM_TIMEOUT_MS) {\n"
    "            fail(\"START_NOT_CONFIRMED\",\n"
    "                \"nPerf no confirmó que el botón Iniciar test hubiera sido activado\");\n"
    "            return;\n"
    "        }",
    "        if (sinceFirstRequest >= START_CONFIRM_TIMEOUT_MS) {\n"
    "            if (ocrInProgress || ocrAttempts < MAX_OCR_ATTEMPTS) {\n"
    "                status(\"OCR_START_VERIFY\",\n"
    "                    \"El nodo Iniciar test sigue visible; verificando el panel final por captura...\");\n"
    "                requestVisualResultOcr(false, now);\n"
    "                return;\n"
    "            }\n"
    "            fail(\"START_NOT_CONFIRMED\",\n"
    "                \"nPerf no mostró un resultado visual verificable después de activar Iniciar test\");\n"
    "            return;\n"
    "        }",
    "start timeout OCR guard",
)

# A generic Chrome toolbar share action must not mark nPerf complete.
service = replace_once(
    service,
    "            \"probar de nuevo\", \"reiniciar test\", \"restart test\", \"reiniciar\",\n"
    "            \"compartir resultado\", \"share result\", \"compartir\",\n"
    "            \"resultado completo\", \"test finalizado\", \"prueba finalizada\");",
    "            \"probar de nuevo\", \"reiniciar test\", \"restart test\",\n"
    "            \"compartir resultado\", \"share result\",\n"
    "            \"resultado completo\", \"test finalizado\", \"prueba finalizada\");",
    "strict accessible completion cues",
)

# Screenshot callback, bitmap conversion and ML Kit must never run on the app main thread.
service = replace_once(
    service,
    "        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),",
    "        takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor,",
    "screenshot background executor",
)

process_pattern = re.compile(
    r'    @SuppressLint\("NewApi"\)\n'
    r'    private void processScreenshotResult\(ScreenshotResult screenshotResult,.*?\n'
    r'    private boolean containsDataFailure\(String normalized\) \{',
    re.DOTALL,
)
process_replacement = r'''    @SuppressLint("NewApi")
    private void processScreenshotResult(ScreenshotResult screenshotResult,
            boolean explicitComplete) {
        HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
        Bitmap hardwareBitmap = null;
        Bitmap softwareBitmap = null;
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer,
                screenshotResult.getColorSpace());
            if (hardwareBitmap != null) {
                softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to convert nPerf screenshot", error);
        } finally {
            buffer.close();
        }

        if (softwareBitmap == null) {
            handler.post(() -> {
                ocrInProgress = false;
                status("OCR_BITMAP_FAILED", "No se pudo preparar la captura nPerf...");
            });
            return;
        }

        Bitmap preparedBitmap = softwareBitmap;
        if (preparedBitmap.getWidth() > 900) {
            int scaledHeight = Math.max(1,
                Math.round(preparedBitmap.getHeight() * 900f / preparedBitmap.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(
                preparedBitmap, 900, scaledHeight, true);
            preparedBitmap.recycle();
            preparedBitmap = scaled;
        }

        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        }

        final Bitmap bitmap = preparedBitmap;
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
            .addOnSuccessListener(ocrExecutor, visionText -> {
                List<NperfScreenshotResultParser.Line> lines = new ArrayList<>();
                for (Text.TextBlock block : visionText.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect bounds = line.getBoundingBox();
                        if (bounds != null && !line.getText().trim().isEmpty()) {
                            lines.add(new NperfScreenshotResultParser.Line(
                                line.getText(), bounds));
                        }
                    }
                }
                NperfBrowserCoordinator.Result visual =
                    NperfScreenshotResultParser.parse(lines, width, height);
                String recognizedText = visionText.getText();
                boolean positionalComplete = hasVisualCompletionCue(lines, width, height);
                handler.post(() -> applyVisualTextResult(visual, recognizedText,
                    explicitComplete || positionalComplete));
            })
            .addOnFailureListener(ocrExecutor, error -> {
                Log.e(TAG, "ML Kit could not read nPerf result", error);
                handler.post(() -> status("OCR_FAILED",
                    "No se pudo leer el panel nPerf; reintentando..."));
            })
            .addOnCompleteListener(ocrExecutor, task -> {
                bitmap.recycle();
                handler.post(() -> ocrInProgress = false);
            });
    }

    private void applyVisualTextResult(NperfBrowserCoordinator.Result visual,
            String recognizedText, boolean visualComplete) {
        if (terminalSent || !syncSession()) return;
        if (!NperfResultParser.hasRequiredMetrics(visual)) {
            ocrStableReads = 0;
            lastOcrSignature = "";
            status("OCR_NO_METRICS",
                "La captura no mostró aún descarga, subida y latencia verificables...");
            Log.i(TAG, "OCR did not produce required nPerf metrics. Text=" +
                (recognizedText == null ? "" : recognizedText.replace('\n', ' ')));
            return;
        }

        long now = SystemClock.elapsedRealtime();
        ensureRunningForVisualResult(now);
        String signature = visual.download + "|" + visual.upload + "|" +
            visual.latency + "|" + visual.jitter;
        if (signature.equals(lastOcrSignature)) {
            ocrStableReads++;
        } else {
            lastOcrSignature = signature;
            ocrStableReads = 1;
        }

        status("OCR_RESULT", "Lectura visual nPerf: ↓ " + visual.download +
            " Mb/s · ↑ " + visual.upload + " Mb/s · Latencia " +
            visual.latency + " ms" + (ocrStableReads >= 2 ? " ✓" : ""));

        long elapsedFromStart = firstStartRequestAt == 0L ? 0L :
            now - firstStartRequestAt;
        if (elapsedFromStart >= MIN_COMPLETE_AFTER_START_MS &&
                (visualComplete || ocrStableReads >= 2)) {
            mergeValidatedMetrics(currentResult, visual);
            resultStableAt = now - STABLE_RESULT_COMPLETE_MS;
            stage = Stage.RESULT_CANDIDATE;
            complete();
        }
    }

    private void ensureRunningForVisualResult(long now) {
        if (startConfirmedAt != 0L) return;
        startConfirmedAt = firstStartRequestAt > 0L ? firstStartRequestAt : now;
        stage = Stage.RUNNING;
        status("START_CONFIRMED_VISUALLY",
            "nPerf confirmado por el panel visual; validando resultado...");
    }

    private boolean hasVisualCompletionCue(
            List<NperfScreenshotResultParser.Line> lines, int width, int height) {
        if (lines == null || width <= 0 || height <= 0) return false;
        for (NperfScreenshotResultParser.Line line : lines) {
            if (line == null || line.bounds == null || line.bounds.isEmpty()) continue;
            float cx = line.bounds.exactCenterX() / Math.max(1f, width);
            float cy = line.bounds.exactCenterY() / Math.max(1f, height);
            // Ignore Chrome's toolbar share icon and bottom browser controls.
            if (cy < 0.12f || cy > 0.82f || cx < 0.12f || cx > 0.92f) continue;
            String value = normalize(line.text);
            if (containsAny(value, "probar de nuevo", "reiniciar test",
                    "restart test", "share result", "compartir resultado") ||
                    ((value.equals("compartir") || value.equals("share")) &&
                     cy > 0.18f && cy < 0.62f)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDataFailure(String normalized) {'''
service, count = process_pattern.subn(lambda _: process_replacement, service, count=1)
if count != 1:
    raise RuntimeError(f"OCR processing block: expected 1 match, found {count}")

service = replace_once(
    service,
    "        if (node == null || depth > 45 || output.size() > 1800) return;",
    "        if (node == null || depth > 30 ||\n"
    "                output.size() >= MAX_ACCESSIBILITY_TEXT_LINES) return;",
    "accessibility traversal cap",
)

# Remove the former visual result method if the regex left a duplicate signature.
if "private void handleVisualTextResult(" in service:
    raise RuntimeError("legacy handleVisualTextResult remained after replacement")

for marker in (
    "Executors.newSingleThreadExecutor",
    "OCR_START_WHILE_UNCONFIRMED_MS",
    "scheduleInspection(120L)",
    "takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor",
    "private void ensureRunningForVisualResult(long now)",
    "MAX_START_ATTEMPTS = 1",
    "MAX_ACCESSIBILITY_TEXT_LINES",
):
    if marker not in service:
        raise RuntimeError(f"missing required marker: {marker}")

gradle = replace_once(gradle, "versionCode 9", "versionCode 10", "versionCode")
gradle = replace_once(
    gradle,
    'versionName "1.9-nperf-visual-results"',
    'versionName "2.0-nperf-ocr-anr-fix"',
    "versionName",
)

normal_workflow = '''name: Android Build

on:
  push:
    branches:
      - main
      - agent/**
  pull_request:
    branches:
      - main
      - agent/stabilize-speedtest-nperf

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Set up Gradle 8.4
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.4'

      - name: Generate Gradle wrapper
        run: gradle wrapper --gradle-version 8.4

      - name: Grant execute permission
        run: chmod +x gradlew

      - name: Run lint
        run: ./gradlew lintRelease --stacktrace

      - name: Build release APK
        run: ./gradlew assembleRelease --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: SpeedtestNL-CustomTab-release
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
          retention-days: 30

      - name: Upload lint report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: SpeedtestNL-CustomTab-lint
          path: app/build/reports/lint-results-release.html
          if-no-files-found: ignore
          retention-days: 14
'''

service_path.write_text(service, encoding="utf-8")
gradle_path.write_text(gradle, encoding="utf-8")
workflow_path.write_text(normal_workflow, encoding="utf-8")
Path(__file__).unlink()
