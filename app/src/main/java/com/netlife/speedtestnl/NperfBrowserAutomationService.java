package com.netlife.speedtestnl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accessibility controller for the public nPerf page running in a Custom Tab.
 *
 * The controller is intentionally conservative: it cannot complete a session
 * until it has submitted a start action, observed that the start control is no
 * longer active, waited for a realistic test duration, and received download
 * and upload values that were not already visible before the test started.
 */
public class NperfBrowserAutomationService extends AccessibilityService {

    private static final String TAG = "SpeedtestNL-nPerfTab";

    private static final long SESSION_TIMEOUT_MS = 7 * 60 * 1000L;
    private static final long PAGE_READY_DELAY_MS = 2500L;
    private static final long START_RETRY_INTERVAL_MS = 9000L;
    private static final long START_CONFIRM_TIMEOUT_MS = 35 * 1000L;
    private static final long START_DATA_TIMEOUT_MS = 150 * 1000L;
    private static final long OCR_START_AFTER_MS = 30 * 1000L;
    private static final long OCR_RETRY_INTERVAL_MS = 5000L;
    private static final long MIN_COMPLETE_AFTER_START_MS = 28 * 1000L;
    private static final long STABLE_RESULT_COMPLETE_MS = 12 * 1000L;
    private static final long STABLE_RESULT_FALLBACK_MIN_MS = 45 * 1000L;
    private static final long ACTION_DEBOUNCE_MS = 1400L;
    private static final long WATCHDOG_INTERVAL_MS = 1800L;
    private static final int MAX_START_ATTEMPTS = 3;
    private static final int MAX_OCR_ATTEMPTS = 12;

    private enum Stage {
        WAITING_PAGE,
        WAITING_START,
        START_REQUESTED,
        RUNNING,
        RESULT_CANDIDATE
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String token = "";
    private String browserPackage = "";
    private long sessionStarted = 0L;
    private long firstPageSeenAt = 0L;
    private long lastActionAt = 0L;
    private long firstStartRequestAt = 0L;
    private long lastStartAttemptAt = 0L;
    private long startConfirmedAt = 0L;
    private long resultStableAt = 0L;

    private int startAttempts = 0;
    private int startMissingObservations = 0;

    private boolean consentHandled = false;
    private boolean locationHandled = false;
    private boolean terminalSent = false;
    private boolean inspecting = false;
    private boolean ocrInProgress = false;
    private boolean lastOcrRequestedAtCompleteScreen = false;

    private int ocrAttempts = 0;
    private int ocrStableReads = 0;
    private long lastOcrAttemptAt = 0L;
    private String lastOcrSignature = "";
    private TextRecognizer textRecognizer;

    private Stage stage = Stage.WAITING_PAGE;
    private String lastResultSignature = "";

    private NperfBrowserCoordinator.Result baselineResult =
        new NperfBrowserCoordinator.Result();
    private NperfBrowserCoordinator.Result currentResult =
        new NperfBrowserCoordinator.Result();

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!syncSession()) return;
            inspectActiveWindow();
            if (syncSession()) handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        Log.i(TAG, "Accessibility controller connected");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !syncSession()) return;

        CharSequence packageValue = event.getPackageName();
        String eventPackage = packageValue == null ? "" : packageValue.toString();
        if (!isExpectedBrowser(eventPackage)) return;

        inspectActiveWindow();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility controller interrupted");
    }

    private void inspectActiveWindow() {
        if (inspecting || terminalSent || !syncSession()) return;
        inspecting = true;
        AccessibilityNodeInfo root = null;
        try {
            long elapsed = SystemClock.elapsedRealtime() - sessionStarted;
            if (elapsed > SESSION_TIMEOUT_MS) {
                fail("SESSION_TIMEOUT",
                    "nPerf no completó la prueba dentro del tiempo máximo");
                return;
            }

            root = getRootInActiveWindow();
            if (root != null) inspect(root);
        } finally {
            if (root != null) root.recycle();
            inspecting = false;
        }
    }

    private boolean syncSession() {
        if (!NperfBrowserCoordinator.isActive(this)) {
            resetLocalSession();
            return false;
        }

        String activeToken = NperfBrowserCoordinator.getToken(this);
        if (!activeToken.equals(token)) {
            token = activeToken;
            browserPackage = NperfBrowserCoordinator.getBrowserPackage(this);
            sessionStarted = NperfBrowserCoordinator.getStartedElapsed(this);
            firstPageSeenAt = 0L;
            lastActionAt = 0L;
            firstStartRequestAt = 0L;
            lastStartAttemptAt = 0L;
            startConfirmedAt = 0L;
            resultStableAt = 0L;
            startAttempts = 0;
            startMissingObservations = 0;
            consentHandled = false;
            locationHandled = false;
            terminalSent = false;
            inspecting = false;
            ocrInProgress = false;
            lastOcrRequestedAtCompleteScreen = false;
            ocrAttempts = 0;
            ocrStableReads = 0;
            lastOcrAttemptAt = 0L;
            lastOcrSignature = "";
            stage = Stage.WAITING_PAGE;
            lastResultSignature = "";
            baselineResult = new NperfBrowserCoordinator.Result();
            currentResult = new NperfBrowserCoordinator.Result();

            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, 800L);
            Log.i(TAG, "New nPerf browser session " + token +
                " package=" + browserPackage);
        }
        return !token.isEmpty() && !terminalSent;
    }

    private void resetLocalSession() {
        handler.removeCallbacks(watchdog);
        token = "";
        browserPackage = "";
        sessionStarted = 0L;
        firstPageSeenAt = 0L;
        terminalSent = false;
        inspecting = false;
        ocrInProgress = false;
        ocrAttempts = 0;
        ocrStableReads = 0;
        lastOcrAttemptAt = 0L;
        lastOcrSignature = "";
    }

    private boolean isExpectedBrowser(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (!browserPackage.isEmpty()) return browserPackage.equals(packageName);

        boolean known = packageName.equals("com.android.chrome") ||
            packageName.equals("com.sec.android.app.sbrowser") ||
            packageName.equals("com.microsoft.emmx") ||
            packageName.equals("org.mozilla.firefox");
        if (known) {
            browserPackage = packageName;
            NperfBrowserCoordinator.setBrowserPackage(this, packageName);
        }
        return known;
    }

    private void inspect(AccessibilityNodeInfo root) {
        List<String> lines = new ArrayList<>();
        collectText(root, lines, 0);
        String joined = join(lines);
        String normalized = normalize(joined);

        if (!looksLikeNperf(normalized)) return;

        long now = SystemClock.elapsedRealtime();
        if (firstPageSeenAt == 0L) firstPageSeenAt = now;
        if (stage == Stage.WAITING_PAGE &&
                now - firstPageSeenAt >= PAGE_READY_DELAY_MS) {
            stage = Stage.WAITING_START;
        }

        if (handleConsent(root, normalized)) return;
        if (handleLocation(root, normalized)) return;

        boolean startVisible = hasStartControl(root);
        NperfBrowserCoordinator.Result observed = extractStrictResult(lines, joined);

        // Capture the page's static/promotional numbers before any start action.
        // They are never allowed to become the final test result.
        if (firstStartRequestAt == 0L) {
            baselineResult.merge(observed);
        }

        if (firstStartRequestAt > 0L && containsDataFailure(normalized)) {
            fail("DATA_CHANNEL_FAILURE",
                "nPerf no pudo recibir datos del servidor de medición");
            return;
        }

        switch (stage) {
            case WAITING_PAGE:
                status("WAITING_PAGE", "Esperando que nPerf prepare el medidor...");
                return;

            case WAITING_START:
                requestStartIfPossible(root, startVisible, now);
                return;

            case START_REQUESTED:
                handleStartRequested(root, startVisible, observed, now);
                return;

            case RUNNING:
            case RESULT_CANDIDATE:
                handleRunning(normalized, observed, now);
                return;

            default:
                return;
        }
    }

    private boolean handleConsent(AccessibilityNodeInfo root, String normalized) {
        if (consentHandled || !containsAny(normalized,
                "politica de uso de cookies", "cookie policy",
                "cookies y privacidad", "cookies and privacy")) {
            return false;
        }

        if (activateTextControl(root, true, "CONSENT",
                "ok", "aceptar", "accept", "agree", "continuar")) {
            consentHandled = true;
            status("CONSENT", "Aceptando cookies nPerf...");
            return true;
        }
        return false;
    }

    private boolean handleLocation(AccessibilityNodeInfo root, String normalized) {
        if (locationHandled || !containsAny(normalized,
                "usar tu ubicacion", "acceder a tu ubicacion",
                "use your location", "access your location") ||
                !containsAny(normalized, "nperf", "nperf.com")) {
            return false;
        }

        if (activateTextControl(root, false, "LOCATION",
                "permitir", "allow", "while using the app",
                "mientras se usa la aplicacion")) {
            locationHandled = true;
            status("LOCATION", "Ubicación web de nPerf autorizada...");
            return true;
        }
        return false;
    }

    private void requestStartIfPossible(AccessibilityNodeInfo root,
            boolean startVisible, long now) {
        if (startAttempts >= MAX_START_ATTEMPTS) {
            if (firstStartRequestAt > 0L &&
                    now - firstStartRequestAt >= START_CONFIRM_TIMEOUT_MS) {
                fail("START_NOT_CONFIRMED",
                    "nPerf mantuvo visible Iniciar test después de " +
                    MAX_START_ATTEMPTS + " intentos controlados");
            }
            return;
        }

        if (lastStartAttemptAt > 0L &&
                now - lastStartAttemptAt < START_RETRY_INTERVAL_MS) {
            status("START_WAIT",
                "Esperando confirmación del inicio de nPerf...");
            return;
        }

        if (startVisible) {
            AccessibilityNodeInfo startNode = findTextNode(root, true,
                new String[]{"iniciar test", "iniciar prueba", "start test",
                    "lancer le test"}, 0);
            if (startNode != null) {
                try {
                    if (activateStartNode(startNode)) return;
                } finally {
                    startNode.recycle();
                }
            }
        }

        long pageElapsed = now - firstPageSeenAt;
        if (pageElapsed >= 7000L) {
            dispatchFallbackStartTap();
        } else {
            status("WAITING", "Buscando el control Iniciar test de nPerf...");
        }
    }

    private void handleStartRequested(AccessibilityNodeInfo root,
            boolean startVisible,
            NperfBrowserCoordinator.Result observed,
            long now) {
        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);
        boolean newThroughput = hasValidThroughput(sanitized);

        if (!startVisible) {
            startMissingObservations++;
        } else {
            startMissingObservations = 0;
        }

        boolean confirmedByDisappearance = startMissingObservations >= 2 &&
            now - lastStartAttemptAt >= 1500L;
        boolean confirmedByLiveData = newThroughput &&
            now - lastStartAttemptAt >= 3000L;

        if (confirmedByDisappearance || confirmedByLiveData) {
            stage = Stage.RUNNING;
            startConfirmedAt = now;
            currentResult = new NperfBrowserCoordinator.Result();
            if (newThroughput) currentResult.merge(sanitized);
            resultStableAt = 0L;
            lastResultSignature = "";
            status("START_CONFIRMED",
                "nPerf confirmó el inicio; esperando descarga y subida...");
            Log.i(TAG, "nPerf start confirmed. disappearance=" +
                confirmedByDisappearance + " data=" + confirmedByLiveData);
            return;
        }

        long sinceFirstRequest = now - firstStartRequestAt;
        if (startVisible && startAttempts < MAX_START_ATTEMPTS &&
                now - lastStartAttemptAt >= START_RETRY_INTERVAL_MS) {
            stage = Stage.WAITING_START;
            requestStartIfPossible(root, true, now);
            return;
        }

        if (sinceFirstRequest >= START_CONFIRM_TIMEOUT_MS) {
            fail("START_NOT_CONFIRMED",
                "nPerf no confirmó que el botón Iniciar test hubiera sido activado");
            return;
        }

        status("START_REQUESTED",
            "Activación enviada; verificando que nPerf realmente inicie...");
    }

    private void handleRunning(String normalized,
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


    @SuppressLint("NewApi")
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

    @SuppressLint("NewApi")
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

    private boolean containsDataFailure(String normalized) {
        return containsAny(normalized,
            "no se pueden recibir datos",
            "no se pudo recibir datos",
            "compruebe su conexion a internet antes de iniciar un test",
            "cannot receive data",
            "unable to receive data",
            "no data received");
    }

    private boolean hasStartControl(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findTextNode(root, true,
            new String[]{"iniciar test", "iniciar prueba", "start test",
                "lancer le test"}, 0);
        if (node == null) return false;
        try {
            return node.isVisibleToUser();
        } finally {
            node.recycle();
        }
    }

    private boolean activateStartNode(AccessibilityNodeInfo node) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;

        if (performClickOnNodeOrParent(node)) {
            markStartRequested("ACCESSIBILITY_CLICK");
            return true;
        }

        Rect bounds = bestTapBounds(node);
        if (!isUsableBounds(bounds)) return false;
        return dispatchTap(bounds.centerX(), bounds.centerY(),
            "START_TEXT_BOUNDS", true);
    }

    private void dispatchFallbackStartTap() {
        if (startAttempts >= MAX_START_ATTEMPTS) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float[] verticalFractions = {0.39f, 0.43f, 0.47f};
        int index = Math.min(startAttempts, verticalFractions.length - 1);
        float x = metrics.widthPixels * 0.50f;
        float y = metrics.heightPixels * verticalFractions[index];
        dispatchTap(x, y, "START_FALLBACK_" + (index + 1), true);
    }

    private boolean activateTextControl(AccessibilityNodeInfo root,
            boolean exact, String source, String... candidates) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;

        AccessibilityNodeInfo node = findTextNode(root, exact, candidates, 0);
        if (node == null) return false;
        try {
            if (performClickOnNodeOrParent(node)) {
                lastActionAt = now;
                return true;
            }
            Rect bounds = bestTapBounds(node);
            return isUsableBounds(bounds) &&
                dispatchTap(bounds.centerX(), bounds.centerY(), source, false);
        } finally {
            node.recycle();
        }
    }

    private boolean performClickOnNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current.isVisibleToUser() && current.isEnabled() &&
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastActionAt = SystemClock.elapsedRealtime();
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private Rect bestTapBounds(AccessibilityNodeInfo node) {
        Rect best = new Rect();
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        try {
            for (int depth = 0; current != null && depth < 7; depth++) {
                Rect candidate = new Rect();
                current.getBoundsInScreen(candidate);
                boolean plausibleControl = isUsableBounds(candidate) &&
                    candidate.width() <= metrics.widthPixels * 0.90f &&
                    candidate.height() <= metrics.heightPixels * 0.30f;
                if (plausibleControl) {
                    best.set(candidate);
                    break;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        return best;
    }

    private boolean isUsableBounds(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return false;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return bounds.centerX() >= 0 && bounds.centerX() <= metrics.widthPixels &&
            bounds.centerY() >= 0 && bounds.centerY() <= metrics.heightPixels &&
            bounds.width() >= 12 && bounds.height() >= 12;
    }

    private boolean dispatchTap(float x, float y,
            final String source, final boolean startAction) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;
        lastActionAt = now;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0L, 140L))
            .build();

        if (startAction) {
            status("START_ACTION", "Activando Iniciar test de nPerf...");
        }

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (startAction) markStartRequested(source);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (startAction && startAttempts >= MAX_START_ATTEMPTS) {
                    fail("START_GESTURE_CANCELLED",
                        "Android canceló el toque sobre Iniciar test");
                }
            }
        }, handler);

        if (!accepted) lastActionAt = 0L;
        return accepted;
    }

    private void markStartRequested(String source) {
        long now = SystemClock.elapsedRealtime();
        startAttempts++;
        if (firstStartRequestAt == 0L) firstStartRequestAt = now;
        lastStartAttemptAt = now;
        startMissingObservations = 0;
        stage = Stage.START_REQUESTED;
        status("START_REQUESTED", "Activación " + startAttempts + "/" +
            MAX_START_ATTEMPTS + " enviada; verificando inicio real...");
        Log.i(TAG, "nPerf start request " + startAttempts + " by " + source);
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo node,
            boolean exact, String[] candidates, int depth) {
        if (node == null || depth > 45) return null;

        String text = normalize(value(node.getText()) + " " +
            value(node.getContentDescription()));
        for (String candidate : candidates) {
            String expected = normalize(candidate);
            if (((exact && text.equals(expected)) ||
                    (!exact && text.contains(expected))) &&
                    node.isVisibleToUser()) {
                return AccessibilityNodeInfo.obtain(node);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found;
            try {
                found = findTextNode(child, exact, candidates, depth + 1);
            } finally {
                child.recycle();
            }
            if (found != null) return found;
        }
        return null;
    }

    private void collectText(AccessibilityNodeInfo node,
            List<String> output, int depth) {
        if (node == null || depth > 45 || output.size() > 1800) return;

        String text = value(node.getText());
        String description = value(node.getContentDescription());
        if (!text.isEmpty()) output.add(text);
        if (!description.isEmpty() && !description.equals(text)) {
            output.add(description);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectText(child, output, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private NperfBrowserCoordinator.Result extractStrictResult(
            List<String> lines, String joined) {
        return NperfResultParser.parse(lines, joined);
    }

    private NperfBrowserCoordinator.Result withoutBaseline(
            NperfBrowserCoordinator.Result observed) {
        NperfBrowserCoordinator.Result result =
            new NperfBrowserCoordinator.Result();
        if (observed == null) return result;

        result.download = different(observed.download, baselineResult.download);
        result.upload = different(observed.upload, baselineResult.upload);
        result.latency = different(observed.latency, baselineResult.latency);
        result.jitter = different(observed.jitter, baselineResult.jitter);
        result.server = observed.server;
        result.operator = observed.operator;
        result.resultId = different(observed.resultId, baselineResult.resultId);
        result.resultUrl = different(observed.resultUrl, baselineResult.resultUrl);
        return result;
    }

    private String different(String observed, String baseline) {
        String value = observed == null ? "" : observed.trim();
        String original = baseline == null ? "" : baseline.trim();
        if (value.isEmpty() || value.equals(original)) return "";
        return value;
    }

    private void mergeValidatedMetrics(NperfBrowserCoordinator.Result target,
            NperfBrowserCoordinator.Result source) {
        if (target == null || source == null) return;
        if (NperfResultParser.positive(source.download)) target.download = source.download;
        if (NperfResultParser.positive(source.upload)) target.upload = source.upload;
        if (NperfResultParser.positive(source.latency)) target.latency = source.latency;
        if (NperfResultParser.positive(source.jitter)) target.jitter = source.jitter;
        if (!source.server.isEmpty()) target.server = source.server;
        if (!source.operator.isEmpty()) target.operator = source.operator;
        if (!source.resultId.isEmpty()) target.resultId = source.resultId;
        if (!source.resultUrl.isEmpty()) target.resultUrl = source.resultUrl;
    }

    private boolean hasValidThroughput(NperfBrowserCoordinator.Result result) {
        return result != null && NperfResultParser.positive(result.download) &&
            NperfResultParser.positive(result.upload);
    }

    private boolean hasCompleteNperfResult(NperfBrowserCoordinator.Result result) {
        return NperfResultParser.hasRequiredMetrics(result);
    }

    private boolean isPositiveMetric(String value) {
        return NperfResultParser.positive(value);
    }

    private boolean isNperfResultUrl(String value) {
        String normalized = normalize(value);
        return normalized.contains("nperf.com") &&
            (normalized.contains("/r/") || normalized.contains("/result"));
    }

    private void status(String state, String detail) {
        NperfBrowserCoordinator.sendStatus(this, token, state, detail);
    }

    private void complete() {
        if (terminalSent || startConfirmedAt == 0L ||
                !hasCompleteNperfResult(currentResult)) {
            return;
        }
        terminalSent = true;
        handler.removeCallbacks(watchdog);
        status("COMPLETE", "Resultado nPerf verificado; regresando a Speedtest NL...");
        NperfBrowserCoordinator.complete(this, token, currentResult);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 350L);
    }

    private void fail(String code, String detail) {
        if (terminalSent) return;
        terminalSent = true;
        handler.removeCallbacks(watchdog);
        Log.w(TAG, code + ": " + detail);
        NperfBrowserCoordinator.fail(this, token, code, detail);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 350L);
    }

    private boolean looksLikeNperf(String normalized) {
        return containsAny(normalized,
            "nperf", "prueba de velocidad de internet", "speed test");
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private boolean containsAny(String source, String... candidates) {
        if (source == null) return false;
        for (String candidate : candidates) {
            if (source.contains(normalize(candidate))) return true;
        }
        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value,
            Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ").trim();
    }

    private String value(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private String prefer(String candidate, String current) {
        return candidate == null || candidate.trim().isEmpty()
            ? (current == null ? "" : current.trim()) : candidate.trim();
    }
}
