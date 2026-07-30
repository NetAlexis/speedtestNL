#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.graphics.Path;\nimport android.graphics.Rect;\n",
    "import android.graphics.Bitmap;\nimport android.graphics.Path;\nimport android.graphics.Rect;\n"
    "import android.hardware.HardwareBuffer;\nimport android.os.Build;\n",
    "screenshot imports",
)
replace_once(
    "import android.view.accessibility.AccessibilityNodeInfo;\n\n",
    "import android.view.Display;\nimport android.view.accessibility.AccessibilityNodeInfo;\n\n"
    "import androidx.annotation.NonNull;\n\n"
    "import com.google.mlkit.vision.common.InputImage;\n"
    "import com.google.mlkit.vision.text.Text;\n"
    "import com.google.mlkit.vision.text.TextRecognition;\n"
    "import com.google.mlkit.vision.text.TextRecognizer;\n"
    "import com.google.mlkit.vision.text.latin.TextRecognizerOptions;\n\n",
    "ML Kit imports",
)

replace_once(
    "    private static final long START_DATA_TIMEOUT_MS = 90 * 1000L;\n",
    "    private static final long START_DATA_TIMEOUT_MS = 150 * 1000L;\n"
    "    private static final long OCR_START_AFTER_MS = 30 * 1000L;\n"
    "    private static final long OCR_RETRY_INTERVAL_MS = 5000L;\n",
    "OCR timing constants",
)
replace_once(
    "    private static final int MAX_START_ATTEMPTS = 3;\n",
    "    private static final int MAX_START_ATTEMPTS = 3;\n"
    "    private static final int MAX_OCR_ATTEMPTS = 12;\n",
    "OCR attempt constant",
)

replace_once(
    "    private boolean inspecting = false;\n\n"
    "    private Stage stage = Stage.WAITING_PAGE;",
    "    private boolean inspecting = false;\n"
    "    private boolean ocrInProgress = false;\n"
    "    private boolean lastOcrRequestedAtCompleteScreen = false;\n\n"
    "    private int ocrAttempts = 0;\n"
    "    private int ocrStableReads = 0;\n"
    "    private long lastOcrAttemptAt = 0L;\n"
    "    private String lastOcrSignature = \"\";\n"
    "    private TextRecognizer textRecognizer;\n\n"
    "    private Stage stage = Stage.WAITING_PAGE;",
    "OCR state fields",
)

replace_once(
    "        Log.i(TAG, \"Accessibility controller connected\");\n"
    "    }\n\n"
    "    @Override\n"
    "    public void onAccessibilityEvent",
    "        if (textRecognizer == null) {\n"
    "            textRecognizer = TextRecognition.getClient(\n"
    "                TextRecognizerOptions.DEFAULT_OPTIONS);\n"
    "        }\n"
    "        Log.i(TAG, \"Accessibility controller connected\");\n"
    "    }\n\n"
    "    @Override\n"
    "    public void onDestroy() {\n"
    "        handler.removeCallbacksAndMessages(null);\n"
    "        if (textRecognizer != null) {\n"
    "            textRecognizer.close();\n"
    "            textRecognizer = null;\n"
    "        }\n"
    "        super.onDestroy();\n"
    "    }\n\n"
    "    @Override\n"
    "    public void onAccessibilityEvent",
    "OCR recognizer lifecycle",
)

replace_once(
    "            terminalSent = false;\n"
    "            inspecting = false;\n"
    "            stage = Stage.WAITING_PAGE;",
    "            terminalSent = false;\n"
    "            inspecting = false;\n"
    "            ocrInProgress = false;\n"
    "            lastOcrRequestedAtCompleteScreen = false;\n"
    "            ocrAttempts = 0;\n"
    "            ocrStableReads = 0;\n"
    "            lastOcrAttemptAt = 0L;\n"
    "            lastOcrSignature = \"\";\n"
    "            stage = Stage.WAITING_PAGE;",
    "reset OCR on new session",
)
replace_once(
    "        terminalSent = false;\n"
    "        inspecting = false;\n"
    "    }",
    "        terminalSent = false;\n"
    "        inspecting = false;\n"
    "        ocrInProgress = false;\n"
    "        ocrAttempts = 0;\n"
    "        ocrStableReads = 0;\n"
    "        lastOcrAttemptAt = 0L;\n"
    "        lastOcrSignature = \"\";\n"
    "    }",
    "reset local OCR state",
)

old_running = '''    private void handleRunning(String normalized,
            NperfBrowserCoordinator.Result observed,
            long now) {
        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);
        mergeValidatedMetrics(currentResult, sanitized);

        long sinceConfirmed = now - startConfirmedAt;
        boolean throughput = hasValidThroughput(currentResult);
        boolean latencyReady = NperfResultParser.positive(currentResult.latency);

        if (!throughput || !latencyReady) {
            if (sinceConfirmed >= START_DATA_TIMEOUT_MS) {
                fail("START_DATA_TIMEOUT",
                    "nPerf confirmó el inicio, pero no produjo descarga, subida y latencia válidas");
                return;
            }
            status("RUNNING", throughput
                ? "nPerf produjo descarga y subida; esperando latencia final..."
                : "nPerf iniciado; esperando datos reales de descarga y subida...");
            return;
        }

        String signature = currentResult.download + "|" +
            currentResult.upload + "|" + currentResult.latency + "|" +
            currentResult.jitter + "|" + currentResult.resultId + "|" +
            currentResult.resultUrl;
        if (!signature.equals(lastResultSignature)) {
            lastResultSignature = signature;
            resultStableAt = now;
        }

        stage = Stage.RESULT_CANDIDATE;
        status("RESULT_CANDIDATE", "nPerf: ↓ " + currentResult.download +
            " Mb/s · ↑ " + currentResult.upload + " Mb/s · Latencia " +
            currentResult.latency + " ms; verificando finalización...");

        boolean explicitComplete = containsAny(normalized,
            "probar de nuevo", "reiniciar test", "restart test",
            "compartir resultado", "share result", "resultado completo",
            "test finalizado", "prueba finalizada");
        boolean resultIdentity = !currentResult.resultId.isEmpty() ||
            isNperfResultUrl(currentResult.resultUrl);
        boolean minimumDuration = sinceConfirmed >= MIN_COMPLETE_AFTER_START_MS;
        boolean stableLongEnough = resultStableAt > 0L &&
            now - resultStableAt >= STABLE_RESULT_COMPLETE_MS;
        boolean fallbackDuration = sinceConfirmed >= STABLE_RESULT_FALLBACK_MIN_MS;

        if (minimumDuration &&
                ((explicitComplete && stableLongEnough) ||
                 (resultIdentity && stableLongEnough) ||
                 (fallbackDuration && stableLongEnough))) {
            complete();
        }
    }
'''
new_running = '''    private void handleRunning(String normalized,
            NperfBrowserCoordinator.Result observed,
            long now) {
        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);
        mergeValidatedMetrics(currentResult, sanitized);

        long sinceConfirmed = now - startConfirmedAt;
        boolean throughput = hasValidThroughput(currentResult);
        boolean latencyReady = NperfResultParser.positive(currentResult.latency);
        boolean explicitComplete = containsAny(normalized,
            "probar de nuevo", "reiniciar test", "restart test", "reiniciar",
            "compartir resultado", "share result", "compartir",
            "resultado completo", "test finalizado", "prueba finalizada");

        if (!throughput || !latencyReady) {
            boolean shouldUseVisualResult = explicitComplete ||
                sinceConfirmed >= OCR_START_AFTER_MS;
            if (shouldUseVisualResult) {
                requestVisualResultOcr(explicitComplete, now);
            }

            if (sinceConfirmed >= START_DATA_TIMEOUT_MS &&
                    !ocrInProgress && ocrAttempts >= MAX_OCR_ATTEMPTS) {
                String reason = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    ? "El navegador no expuso las métricas y Android no permite capturar la pantalla en esta versión"
                    : "nPerf terminó visualmente, pero no se pudieron verificar descarga, subida y latencia después de " +
                      MAX_OCR_ATTEMPTS + " lecturas";
                fail("VISUAL_RESULT_NOT_READ", reason);
                return;
            }

            if (ocrInProgress) {
                status("OCR_READING", "nPerf finalizó. Leyendo visualmente descarga, subida y latencia...");
            } else if (shouldUseVisualResult) {
                status("OCR_WAIT", "Esperando una lectura visual válida del resultado nPerf...");
            } else {
                status("RUNNING", throughput
                    ? "nPerf produjo descarga y subida; esperando latencia final..."
                    : "nPerf iniciado; esperando datos reales de descarga y subida...");
            }
            return;
        }

        String signature = currentResult.download + "|" +
            currentResult.upload + "|" + currentResult.latency + "|" +
            currentResult.jitter + "|" + currentResult.resultId + "|" +
            currentResult.resultUrl;
        if (!signature.equals(lastResultSignature)) {
            lastResultSignature = signature;
            resultStableAt = now;
        }

        stage = Stage.RESULT_CANDIDATE;
        status("RESULT_CANDIDATE", "nPerf: ↓ " + currentResult.download +
            " Mb/s · ↑ " + currentResult.upload + " Mb/s · Latencia " +
            currentResult.latency + " ms; verificando finalización...");

        boolean resultIdentity = !currentResult.resultId.isEmpty() ||
            isNperfResultUrl(currentResult.resultUrl);
        boolean minimumDuration = sinceConfirmed >= MIN_COMPLETE_AFTER_START_MS;
        boolean stableLongEnough = resultStableAt > 0L &&
            now - resultStableAt >= STABLE_RESULT_COMPLETE_MS;
        boolean fallbackDuration = sinceConfirmed >= STABLE_RESULT_FALLBACK_MIN_MS;

        if (minimumDuration &&
                ((explicitComplete && stableLongEnough) ||
                 (resultIdentity && stableLongEnough) ||
                 (fallbackDuration && stableLongEnough))) {
            complete();
        }
    }
'''
replace_once(old_running, new_running, "strict running/OCR flow")

ocr_methods = r'''
    private void requestVisualResultOcr(boolean explicitComplete, long now) {
        if (terminalSent || ocrInProgress || ocrAttempts >= MAX_OCR_ATTEMPTS) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (lastOcrAttemptAt > 0L && now - lastOcrAttemptAt < OCR_RETRY_INTERVAL_MS) return;

        ocrInProgress = true;
        ocrAttempts++;
        lastOcrAttemptAt = now;
        lastOcrRequestedAtCompleteScreen = explicitComplete;
        status("OCR_CAPTURE", "Leyendo panel final nPerf (" + ocrAttempts + "/" +
            MAX_OCR_ATTEMPTS + ")...");

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                    processScreenshotResult(screenshotResult,
                        lastOcrRequestedAtCompleteScreen);
                }

                @Override
                public void onFailure(int errorCode) {
                    ocrInProgress = false;
                    Log.w(TAG, "nPerf screenshot failed: " + errorCode);
                    status("OCR_CAPTURE_FAILED",
                        "No se pudo capturar el panel nPerf; se reintentará...");
                }
            });
    }

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
            ocrInProgress = false;
            status("OCR_BITMAP_FAILED", "No se pudo preparar la captura nPerf...");
            return;
        }

        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        }

        final Bitmap bitmap = softwareBitmap;
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
            .addOnSuccessListener(visionText ->
                handleVisualTextResult(visionText, width, height, explicitComplete))
            .addOnFailureListener(error -> {
                Log.e(TAG, "ML Kit could not read nPerf result", error);
                status("OCR_FAILED", "No se pudo leer el panel nPerf; reintentando...");
            })
            .addOnCompleteListener(task -> {
                bitmap.recycle();
                ocrInProgress = false;
            });
    }

    private void handleVisualTextResult(Text visionText, int width, int height,
            boolean explicitComplete) {
        if (terminalSent || !syncSession()) return;

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
        if (!NperfResultParser.hasRequiredMetrics(visual)) {
            ocrStableReads = 0;
            lastOcrSignature = "";
            status("OCR_NO_METRICS", "La captura no mostró aún descarga, subida y latencia verificables...");
            Log.i(TAG, "OCR did not produce required nPerf metrics. Text=" +
                visionText.getText().replace('\n', ' '));
            return;
        }

        String signature = visual.download + "|" + visual.upload + "|" +
            visual.latency + "|" + visual.jitter;
        if (signature.equals(lastOcrSignature)) {
            ocrStableReads++;
        } else {
            lastOcrSignature = signature;
            ocrStableReads = 1;
        }

        boolean visualComplete = explicitComplete || containsAny(
            normalize(visionText.getText()), "compartir", "share", "reiniciar",
            "restart", "probar de nuevo");
        status("OCR_RESULT", "Lectura visual nPerf: ↓ " + visual.download +
            " Mb/s · ↑ " + visual.upload + " Mb/s · Latencia " +
            visual.latency + " ms" + (ocrStableReads >= 2 ? " ✓" : ""));

        if (visualComplete || ocrStableReads >= 2) {
            mergeValidatedMetrics(currentResult, visual);
            resultStableAt = SystemClock.elapsedRealtime() -
                STABLE_RESULT_COMPLETE_MS;
            stage = Stage.RESULT_CANDIDATE;
            complete();
        }
    }

'''
replace_once(
    "    private boolean containsDataFailure(String normalized) {\n",
    ocr_methods + "    private boolean containsDataFailure(String normalized) {\n",
    "insert OCR methods",
)

for marker in (
    "android:canTakeScreenshot",  # checked in XML separately by CI grep
    "requestVisualResultOcr(explicitComplete, now)",
    "NperfScreenshotResultParser.parse(lines, width, height)",
    "TextRecognition.getClient",
    "VISUAL_RESULT_NOT_READ",
):
    if marker == "android:canTakeScreenshot":
        continue
    if marker not in text:
        raise RuntimeError(f"missing service marker: {marker}")

path.write_text(text, encoding="utf-8")
