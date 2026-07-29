#!/usr/bin/env python3
from pathlib import Path
import re

path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.os.PowerManager;\n",
    "import android.os.PowerManager;\nimport android.util.Log;\n",
    "Log import",
)

replace_once(
    "    private NperfAutomation nperfAutomation;\n"
    "    private int nperfPollingSession = 0;",
    "    private NperfAutomation nperfAutomation;\n"
    "    private int nperfPollingSession = 0;\n"
    "    private int nperfCompatibilityAttempt = 0;\n"
    "    private String nperfEngineDiagnostic = \"\";",
    "nPerf diagnostic fields",
)

replace_once(
    "        nperfRetry = 0;\n"
    "        nperfTransitionStarted.set(false);",
    "        nperfRetry = 0;\n"
    "        nperfCompatibilityAttempt = 0;\n"
    "        nperfEngineDiagnostic = \"\";\n"
    "        nperfTransitionStarted.set(false);",
    "reset nPerf compatibility state",
)

replace_once(
    "                if (!isRunning) {\n"
    "                    watcherRunning = false;\n"
    "                    return;\n"
    "                }",
    "                if (!isRunning || !watcherRunning || !\"speedtest\".equals(phase)) {\n"
    "                    watcherRunning = false;\n"
    "                    return;\n"
    "                }",
    "stop banner watcher outside Speedtest",
)

replace_once(
    "                if (isRunning) handler.postDelayed(this, 2000);\n"
    "                else watcherRunning = false;",
    "                if (isRunning && watcherRunning && \"speedtest\".equals(phase)) {\n"
    "                    handler.postDelayed(this, 2000);\n"
    "                } else {\n"
    "                    watcherRunning = false;\n"
    "                }",
    "banner watcher reschedule guard",
)

replace_once(
    "        s.setDomStorageEnabled(true);\n"
    "        s.setCacheMode(WebSettings.LOAD_NO_CACHE);\n"
    "        s.setDatabaseEnabled(false);\n"
    "        s.setSaveFormData(false);",
    "        s.setDomStorageEnabled(true);\n"
    "        s.setDatabaseEnabled(true);\n"
    "        s.setCacheMode(WebSettings.LOAD_DEFAULT);\n"
    "        s.setSaveFormData(false);\n"
    "        s.setLoadsImagesAutomatically(true);\n"
    "        s.setMediaPlaybackRequiresUserGesture(false);\n"
    "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {\n"
    "            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);\n"
    "        }\n"
    "        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);",
    "WebView compatibility settings",
)

chrome_marker = "            @Override\n            public void onProgressChanged(WebView view, int progress) {"
chrome_insert = '''            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String message = consoleMessage == null ? "" : consoleMessage.message();
                String source = consoleMessage == null ? "" : consoleMessage.sourceId();
                int line = consoleMessage == null ? 0 : consoleMessage.lineNumber();
                Log.d("SpeedtestNL-Web", source + ":" + line + " " + message);
                if ("nperf".equals(phase) && isNperfEngineFailureText(message)) {
                    handleNperfEngineFailure(message);
                }
                return true;
            }

'''
replace_once(chrome_marker, chrome_insert + chrome_marker, "WebView console diagnostics")

client_marker = "            @Override\n            public void onReceivedError(WebView view, int errorCode,"
client_insert = '''            @Override
            public void onReceivedHttpError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceResponse errorResponse) {
                if (request != null && errorResponse != null) {
                    Log.w("SpeedtestNL-Web", "HTTP " + errorResponse.getStatusCode() +
                        " " + request.getUrl());
                }
            }

'''
replace_once(client_marker, client_insert + client_marker, "HTTP diagnostics")

listener_old = '''                @Override
                public void onManualStartAvailable() {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    setStatus("nperf no inició automáticamente. Puede tocar Iniciar test; no se recargará.");
                    SpeedtestService.update(MainActivity.this,
                        "nperf esperando inicio manual - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                }
'''
listener_new = listener_old + '''
                @Override
                public void onEngineError(String message) {
                    handleNperfEngineFailure(message);
                }
'''
replace_once(listener_old, listener_new, "nPerf engine error listener")

setup_marker = "    // ══════════════════════════════════════════════════════════════════════\n    // NPERF — Iniciar prueba"
setup_helpers = '''    private boolean isNperfEngineFailureText(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("no fue posible inicializar") ||
            lower.contains("no se pudo inicializar") ||
            lower.contains("error al inicializar") ||
            lower.contains("unable to initialize") ||
            lower.contains("could not initialize") ||
            lower.contains("initialization failed") ||
            lower.contains("impossible d'initialiser");
    }

    private void handleNperfEngineFailure(String message) {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (!nErrorDetected.compareAndSet(false, true)) return;

        nperfEngineDiagnostic = message == null ? "" : message.trim();
        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        nperfPollingStarted.set(false);

        if (nperfCompatibilityAttempt == 0) {
            nperfCompatibilityAttempt = 1;
            setStatus("nperf no inicializó. Aplicando modo compatible...");
            SpeedtestService.update(this,
                "nperf: reintentando inicialización compatible",
                "Prueba " + currentRun + " de " + totalRuns);
            handler.postDelayed(this::reloadNperfCompatibilityMode, 1400L);
        } else {
            String detail = nperfEngineDiagnostic.isEmpty()
                ? "motor no disponible" : nperfEngineDiagnostic;
            setStatus("nperf no pudo inicializar: " + detail);
            SpeedtestService.update(this,
                "nperf no pudo inicializar - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);
        }
    }

    private void reloadNperfCompatibilityMode() {
        if (!"nperf".equals(phase) || nSaved.get() || webView == null) return;
        nErrorDetected.set(false);
        nGoPressed = false;
        nPageLoaded = false;
        nPollCount = 0;
        nperfPollingStarted.set(false);
        applyNperfWebProfile(true);
        webView.stopLoading();
        webView.loadUrl(NPERF_URL + "?stnl=" + System.currentTimeMillis());
    }

'''
replace_once(setup_marker, setup_helpers + setup_marker, "nPerf engine failure helpers")

replace_once(
    "        if (nperfAutomation != null) nperfAutomation.cancel();\n"
    "        nperfPollingSession++;\n"
    "        nGoPressed = false; nPageLoaded = false; nPollCount = 0;",
    "        if (nperfAutomation != null) nperfAutomation.cancel();\n"
    "        nperfPollingSession++;\n"
    "        nperfCompatibilityAttempt = 0;\n"
    "        nperfEngineDiagnostic = \"\";\n"
    "        nGoPressed = false; nPageLoaded = false; nPollCount = 0;",
    "start nPerf compatibility reset",
)

replace_once(
    "        prepareNperfSession();\n"
    "        setNperfUserAgent();\n"
    "        webView.loadUrl(NPERF_URL);",
    "        prepareNperfSession();\n"
    "        applyNperfWebProfile(false);\n"
    "        webView.loadUrl(NPERF_URL);",
    "apply nPerf WebView profile",
)

ua_pattern = re.compile(
    r"    private void setSpeedtestUserAgent\(\) \{.*?\n    private void clearWebViewSession",
    re.DOTALL,
)
ua_replacement = '''    private void setSpeedtestUserAgent() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(SPEEDTEST_USER_AGENT);
        settings.setTextZoom(30);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
    }

    private String buildNperfDesktopUserAgent(boolean compatibilityMode) {
        String defaultUa = WebSettings.getDefaultUserAgent(this);
        Matcher chrome = Pattern.compile("Chrome/([0-9.]+)").matcher(defaultUa);
        String version = chrome.find() ? chrome.group(1) : "131.0.0.0";
        String platform = compatibilityMode
            ? "X11; Linux x86_64" : "Windows NT 10.0; Win64; x64";
        return "Mozilla/5.0 (" + platform + ") " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version +
            " Safari/537.36";
    }

    private void applyNperfWebProfile(boolean compatibilityMode) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(buildNperfDesktopUserAgent(compatibilityMode));
        settings.setTextZoom(100);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        android.webkit.CookieManager cookies =
            android.webkit.CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }
        cookies.flush();

        if (compatibilityMode) webView.clearCache(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.pm.PackageInfo webViewPackage =
                    WebView.getCurrentWebViewPackage();
                if (webViewPackage != null) {
                    Log.i("SpeedtestNL-Web", "WebView " + webViewPackage.packageName +
                        " " + webViewPackage.versionName +
                        " UA=" + settings.getUserAgentString());
                }
            } catch (Exception ignored) { }
        }
    }

    private void setNperfUserAgent() {
        applyNperfWebProfile(false);
    }

    private void clearWebViewSession'''
text, count = ua_pattern.subn(lambda _: ua_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"WebView profile methods: expected 1 match, found {count}")

replace_once(
    "    private void transitionToNperf() {\n"
    "        if (!nperfTransitionStarted.compareAndSet(false, true)) return;\n"
    "        phase = \"nperf\";",
    "    private void transitionToNperf() {\n"
    "        if (!nperfTransitionStarted.compareAndSet(false, true)) return;\n"
    "        stopBannerWatcher();\n"
    "        phase = \"nperf\";",
    "stop universal watcher before nPerf",
)

error_old = '''            if ("true".equals(hasError) && nErrorDetected.compareAndSet(false, true)) {
                handler.post(() -> {
                    if (!isConnected()) showNoInternetDialog();
                    else {
                        setStatus("Error en nperf - reintentando en 5s...");
                        handler.postDelayed(this::retryNperf, 5000);
                    }
                });
                return;
            }
'''
error_new = '''            if ("true".equals(hasError)) {
                handler.post(() -> {
                    if (!isConnected()) showNoInternetDialog();
                    else handleNperfEngineFailure("nPerf reportó un error de inicialización");
                });
                return;
            }
'''
replace_once(error_old, error_new, "nPerf polling error handling")

required = (
    "private void handleNperfEngineFailure(String message)",
    "settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW)",
    "stopBannerWatcher();\n        phase = \"nperf\";",
    "onEngineError(String message)",
    "!isRunning || !watcherRunning || !\"speedtest\".equals(phase)",
)
for marker in required:
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

path.write_text(text, encoding="utf-8")
