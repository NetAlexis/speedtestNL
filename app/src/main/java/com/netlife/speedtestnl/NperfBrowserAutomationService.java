package com.netlife.speedtestnl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic accessibility controller for the public nPerf page running in
 * a Custom Tab. It remains inert unless NperfBrowserCoordinator has an active
 * token and ignores every package except the selected browser.
 */
public class NperfBrowserAutomationService extends AccessibilityService {

    private static final String TAG = "SpeedtestNL-nPerfTab";
    private static final long SESSION_TIMEOUT_MS = 7 * 60 * 1000L;
    private static final long START_DATA_TIMEOUT_MS = 50 * 1000L;
    private static final long RESULT_STABLE_MS = 8 * 1000L;
    private static final long ACTION_DEBOUNCE_MS = 1800L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String token = "";
    private String browserPackage = "";
    private long sessionStarted = 0L;
    private long startActionAt = 0L;
    private long lastActionAt = 0L;
    private long resultStableAt = 0L;
    private String lastResultSignature = "";
    private boolean consentHandled = false;
    private boolean locationHandled = false;
    private boolean startActivated = false;
    private boolean fallbackTapSent = false;
    private boolean terminalSent = false;
    private NperfBrowserCoordinator.Result currentResult =
        new NperfBrowserCoordinator.Result();

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
        Log.i(TAG, "Accessibility controller connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !syncSession()) return;

        CharSequence packageValue = event.getPackageName();
        String eventPackage = packageValue == null ? "" : packageValue.toString();
        if (!isExpectedBrowser(eventPackage)) return;

        long elapsed = SystemClock.elapsedRealtime() - sessionStarted;
        if (elapsed > SESSION_TIMEOUT_MS) {
            fail("SESSION_TIMEOUT",
                "nPerf no completó la prueba dentro del tiempo máximo");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            inspect(root);
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility controller interrupted");
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
            startActionAt = 0L;
            lastActionAt = 0L;
            resultStableAt = 0L;
            lastResultSignature = "";
            consentHandled = false;
            locationHandled = false;
            startActivated = false;
            fallbackTapSent = false;
            terminalSent = false;
            currentResult = new NperfBrowserCoordinator.Result();
            Log.i(TAG, "New nPerf browser session " + token +
                " package=" + browserPackage);
        }
        return !token.isEmpty() && !terminalSent;
    }

    private void resetLocalSession() {
        token = "";
        browserPackage = "";
        sessionStarted = 0L;
        terminalSent = false;
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

        String dataFailure = firstContaining(normalized,
            "no se pueden recibir datos",
            "no se pudo recibir datos",
            "compruebe su conexion a internet antes de iniciar un test",
            "cannot receive data",
            "unable to receive data",
            "no data received");
        if (!dataFailure.isEmpty()) {
            fail("DATA_CHANNEL_FAILURE",
                "nPerf no pudo recibir datos del servidor de medición");
            return;
        }

        if (!consentHandled && containsAny(normalized,
                "politica de uso de cookies", "cookie policy",
                "cookies y privacidad", "cookies and privacy")) {
            if (clickText(root, true,
                    "ok", "aceptar", "accept", "agree", "continuar")) {
                consentHandled = true;
                status("CONSENT", "Aceptando cookies nPerf...");
                return;
            }
        }

        if (!locationHandled && containsAny(normalized,
                "usar tu ubicacion", "acceder a tu ubicacion",
                "use your location", "access your location") &&
                containsAny(normalized, "nperf", "nperf.com")) {
            if (clickText(root, false,
                    "permitir", "allow", "while using the app",
                    "mientras se usa la aplicacion")) {
                locationHandled = true;
                status("LOCATION", "Ubicación web de nPerf autorizada...");
                return;
            }
        }

        NperfBrowserCoordinator.Result observed = extractResult(lines, joined);
        currentResult.merge(observed);
        boolean throughput = currentResult.hasThroughput();
        boolean latencyOnly = !throughput &&
            (!currentResult.latency.isEmpty() || !currentResult.jitter.isEmpty());

        if (throughput) {
            String signature = currentResult.download + "|" +
                currentResult.upload + "|" + currentResult.latency + "|" +
                currentResult.jitter;
            long now = SystemClock.elapsedRealtime();
            if (!signature.equals(lastResultSignature)) {
                lastResultSignature = signature;
                resultStableAt = now;
            }
            status("RUNNING", "nPerf: ↓ " + currentResult.download +
                " Mb/s · ↑ " + currentResult.upload + " Mb/s");

            boolean explicitComplete = containsAny(normalized,
                "probar de nuevo", "reiniciar test", "restart test",
                "compartir resultado", "share result", "resultado completo");
            if (explicitComplete || now - resultStableAt >= RESULT_STABLE_MS) {
                complete();
            }
            return;
        }

        if (latencyOnly) {
            status("LATENCY", "nPerf midiendo latencia; esperando descarga...");
        }

        boolean startVisible = containsAny(normalized,
            "iniciar test", "iniciar prueba", "start test", "lancer le test");

        if (!startActivated && startVisible) {
            if (clickText(root, false,
                    "iniciar test", "iniciar prueba", "start test",
                    "lancer le test")) {
                markStartActivated("START_NODE");
                return;
            }
        }

        long elapsed = SystemClock.elapsedRealtime() - sessionStarted;
        if (!startActivated && !fallbackTapSent && elapsed >= 7000L &&
                containsAny(normalized, "speed test", "prueba de velocidad")) {
            fallbackTapSent = true;
            dispatchMeterTap();
            return;
        }

        if (startActivated) {
            long sinceStart = SystemClock.elapsedRealtime() - startActionAt;
            if (sinceStart >= START_DATA_TIMEOUT_MS) {
                fail("START_DATA_TIMEOUT",
                    latencyOnly
                        ? "nPerf obtuvo latencia, pero no inició descarga ni subida"
                        : "nPerf recibió la activación, pero no inició la transferencia de datos");
                return;
            }
            status("CONNECTING",
                latencyOnly
                    ? "nPerf midiendo latencia; esperando descarga..."
                    : "nPerf iniciado; esperando conexión con el servidor...");
        } else {
            status("WAITING", "Buscando el control Iniciar test de nPerf...");
        }
    }

    private void markStartActivated(String source) {
        startActivated = true;
        startActionAt = SystemClock.elapsedRealtime();
        status("STARTED", "Iniciar test activado; esperando datos de nPerf...");
        Log.i(TAG, "nPerf start activated by " + source);
    }

    private void dispatchMeterTap() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return;
        lastActionAt = now;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float x = metrics.widthPixels * 0.50f;
        float y = metrics.heightPixels * 0.43f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0L, 120L))
            .build();

        status("START_FALLBACK", "Activando el medidor nPerf...");
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                markStartActivated("SCREEN_GESTURE");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                fail("START_GESTURE_CANCELLED",
                    "Android canceló el toque sobre Iniciar test");
            }
        }, handler);
    }

    private boolean clickText(AccessibilityNodeInfo root, boolean exact,
            String... candidates) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;
        AccessibilityNodeInfo node = findTextNode(root, exact, candidates, 0);
        if (node == null) return false;
        try {
            AccessibilityNodeInfo current = node;
            for (int depth = 0; current != null && depth < 7; depth++) {
                if (current.isClickable() && current.isEnabled() &&
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastActionAt = now;
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (current != node) current.recycle();
                current = parent;
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            node.recycle();
        }
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo node,
            boolean exact, String[] candidates, int depth) {
        if (node == null || depth > 40) return null;
        String text = normalize(value(node.getText()) + " " +
            value(node.getContentDescription()));
        for (String candidate : candidates) {
            String expected = normalize(candidate);
            if ((exact && text.equals(expected)) ||
                    (!exact && text.contains(expected))) {
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
        if (node == null || depth > 40 || output.size() > 1500) return;
        String text = value(node.getText());
        String description = value(node.getContentDescription());
        if (!text.isEmpty()) output.add(text);
        if (!description.isEmpty() && !description.equals(text)) output.add(description);
        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) output.add(viewId);

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

    private NperfBrowserCoordinator.Result extractResult(
            List<String> lines, String joined) {
        NperfBrowserCoordinator.Result result =
            NperfBrowserCoordinator.parseSharedText(joined);
        result.download = prefer(result.download,
            metricNearLabel(lines, "download", "descarga", "bajada"));
        result.upload = prefer(result.upload,
            metricNearLabel(lines, "upload", "subida", "carga"));
        result.latency = prefer(result.latency,
            metricNearLabel(lines, "latency", "latencia", "ping"));
        result.jitter = prefer(result.jitter,
            metricNearLabel(lines, "jitter"));

        for (String line : lines) {
            String normalized = normalize(line);
            if (result.resultUrl.isEmpty() && normalized.contains("nperf.com") &&
                    (normalized.contains("/r/") || normalized.contains("/result"))) {
                Matcher matcher = Pattern.compile(
                    "(?i)(https?://[^\\s]+)").matcher(line);
                if (matcher.find()) result.resultUrl = matcher.group(1);
            }
            if (result.resultId.isEmpty()) {
                Matcher matcher = Pattern.compile(
                    "(?i)(?:result(?:ado)?\\s*id|id de resultado)\\D{0,8}([A-Za-z0-9_-]{5,})")
                    .matcher(line);
                if (matcher.find()) result.resultId = matcher.group(1);
            }
        }
        return result;
    }

    private String metricNearLabel(List<String> lines, String... labels) {
        for (int i = 0; i < lines.size(); i++) {
            String normalized = normalize(lines.get(i));
            if (!containsAny(normalized, labels)) continue;
            for (int distance = 0; distance <= 6; distance++) {
                int[] indexes = {i + distance, i - distance};
                for (int index : indexes) {
                    if (index < 0 || index >= lines.size()) continue;
                    String value = numericValue(lines.get(index));
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    private String numericValue(String text) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z])([0-9]+(?:[.,][0-9]+)?)")
            .matcher(text == null ? "" : text);
        if (!matcher.find()) return "";
        return matcher.group(1).replace(',', '.');
    }

    private void status(String state, String detail) {
        NperfBrowserCoordinator.sendStatus(this, token, state, detail);
    }

    private void complete() {
        if (terminalSent || !currentResult.hasThroughput()) return;
        terminalSent = true;
        status("COMPLETE", "Resultado nPerf completo; regresando a Speedtest NL...");
        NperfBrowserCoordinator.complete(this, token, currentResult);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 250L);
    }

    private void fail(String code, String detail) {
        if (terminalSent) return;
        terminalSent = true;
        Log.w(TAG, code + ": " + detail);
        NperfBrowserCoordinator.fail(this, token, code, detail);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 250L);
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

    private String firstContaining(String source, String... candidates) {
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (source.contains(normalized)) return normalized;
        }
        return "";
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
