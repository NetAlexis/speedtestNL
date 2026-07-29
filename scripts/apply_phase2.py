#!/usr/bin/env python3
from pathlib import Path
import re

SOURCE = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = SOURCE.read_text(encoding="utf-8")
original = text


def replace_literal(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 literal match, found {count}")
    text = text.replace(old, new, 1)


def replace_regex(pattern: str, replacement: str, label: str, flags: int = re.DOTALL) -> None:
    global text
    updated, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 regex match, found {count}")
    text = updated


# Coordination guards and stable endpoint/user-agent constants.
replace_literal(
    '    private boolean watcherRunning = false;   // banner watcher activo\n',
    '    private boolean watcherRunning = false;   // banner watcher activo\n'
    '    private final AtomicBoolean nperfTransitionStarted = new AtomicBoolean(false);\n'
    '    private final AtomicBoolean finalSaveStarted = new AtomicBoolean(false);\n'
    '    private int nperfRetry = 0;\n',
    'coordination fields',
)

replace_literal(
    '    private static final int MAX_POLL       = 120; // 6 minutos\n',
    '    private static final int MAX_POLL       = 120; // 6 minutos\n\n'
    '    private static final String SPEEDTEST_URL = "https://www.speedtest.net/en";\n'
    '    private static final String NPERF_URL = "https://www.nperf.com/es/";\n'
    '    private static final String SPEEDTEST_USER_AGENT =\n'
    '        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +\n'
    '        "AppleWebKit/537.36 (KHTML, like Gecko) " +\n'
    '        "Chrome/120.0.0.0 Safari/537.36";\n'
    '    private static final String NPERF_USER_AGENT =\n'
    '        "Mozilla/5.0 (Linux; Android 12; Mobile) " +\n'
    '        "AppleWebKit/537.36 (KHTML, like Gecko) " +\n'
    '        "Chrome/120.0.0.0 Mobile Safari/537.36";\n',
    'stable URL and user-agent constants',
)

# Do not restart Speedtest when the user chooses to continue from the back dialog.
replace_regex(
    r'                        \.setNegativeButton\("No, continuar", \(d2, w2\) -> \{.*?\n                        \}\)\n                        \.show\(\);',
    '''                        .setNegativeButton("No, continuar", (d2, w2) -> {
                            setStatus("Continuando prueba " + currentRun + "...");
                            if (webView != null) webView.resumeTimers();
                        })
                        .show();''',
    'back-dialog continue action',
)

# Start each run with the known-working Speedtest behavior, but through one reload path.
replace_regex(
    r'    private void startRun\(\) \{.*?\n    \}\n\n    private void resetState\(\)',
    '''    private void startRun() {
        if (!isWifiConnected()) {
            showNoWifiDialog(this::startRun);
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog(this::startRun);
            return;
        }

        isRunning = true;
        currentRun++;
        currentRetry = 0;
        phase = "speedtest";
        resetState();

        String progress = "Prueba " + currentRun + " de " + totalRuns;
        tvCounter.setText(progress);
        setStatus("Iniciando " + currentRun + "/" + totalRuns + "...");
        SpeedtestService.update(this, "Iniciando prueba " + currentRun, progress);

        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
        startBannerWatcher();
        reloadSpeedtestCurrentAttempt();
    }

    private void resetState()''',
    'startRun method',
)

replace_regex(
    r'    private void resetState\(\) \{.*?\n    \}\n\n    // ═+\n    // BANNER WATCHER',
    '''    private void resetState() {
        resultId = ""; resultUrl = ""; download = ""; upload = "";
        ping = ""; jitter = ""; pageLoaded = false;
        goPressed = false; pollCount = 0;
        saved.set(false);
        errorDetected.set(false);

        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = ""; nDone = false;
        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nSaved.set(false);
        nErrorDetected.set(false);

        nperfRetry = 0;
        nperfTransitionStarted.set(false);
        finalSaveStarted.set(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // BANNER WATCHER''',
    'resetState method',
)

# Connection dialogs now resume the requested operation instead of incrementing or changing phase.
replace_regex(
    r'    private void showNoWifiDialog\(\) \{.*?\n    \}\n\n    private void showNoInternetDialog\(\)',
    '''    private void showNoWifiDialog() {
        showNoWifiDialog(this::resumeCurrentPhaseAfterConnection);
    }

    private void showNoWifiDialog(Runnable resumeAction) {
        handler.post(() -> {
            setStatus("Requiere conexion WiFi...");
            SpeedtestService.update(this, "Sin WiFi — esperando conexion", "");

            new AlertDialog.Builder(this)
                .setTitle("Conexion WiFi requerida")
                .setMessage("Esta prueba requiere estar conectado a una red WiFi.\\n\\n" +
                    "Por favor conectese a WiFi y presione Aceptar.")
                .setPositiveButton("Aceptar", (d, w) -> {
                    if (isWifiConnected()) {
                        setStatus("WiFi conectado. Continuando...");
                        handler.postDelayed(resumeAction, 1000);
                    } else {
                        handler.postDelayed(() -> showNoWifiDialog(resumeAction), 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void showNoInternetDialog()''',
    'WiFi dialog methods',
)

replace_regex(
    r'    private void showNoInternetDialog\(\) \{.*?\n    \}\n\n    private void retryRun\(\)',
    '''    private void showNoInternetDialog() {
        showNoInternetDialog(this::resumeCurrentPhaseAfterConnection);
    }

    private void showNoInternetDialog(Runnable resumeAction) {
        handler.post(() -> {
            setStatus("Sin conexion a internet...");
            SpeedtestService.update(this, "Sin datos — esperando conexion", "");

            new AlertDialog.Builder(this)
                .setTitle("Sin Datos")
                .setMessage("No hay conexion a internet.\\n\\n" +
                    "Verifique su conexion y presione Aceptar para continuar.")
                .setPositiveButton("Aceptar", (d, w) -> {
                    if (isConnected()) {
                        setStatus("Conexion restaurada. Continuando prueba " + currentRun + "...");
                        SpeedtestService.update(this,
                            "Conexion restaurada - continuando prueba " + currentRun,
                            "Prueba " + currentRun + " de " + totalRuns);
                        handler.postDelayed(resumeAction, 1000);
                    } else {
                        handler.postDelayed(() -> showNoInternetDialog(resumeAction), 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void retryRun()''',
    'internet dialog methods',
)

replace_regex(
    r'    private void retryRun\(\) \{.*?\n    \}\n\n    private void showErrorDialog\(\)',
    '''    private void retryRun() {
        if (currentRetry < maxRetries) {
            currentRetry++;
            int runActual = currentRun;
            setStatus("Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")...");
            SpeedtestService.update(this,
                "Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")",
                "Prueba " + runActual + " de " + totalRuns);

            resultId = ""; resultUrl = ""; download = ""; upload = "";
            ping = ""; jitter = ""; pageLoaded = false;
            goPressed = false; pollCount = 0;
            saved.set(false);
            errorDetected.set(false);
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 3000);
        } else {
            showErrorDialog();
        }
    }

    private void showErrorDialog()''',
    'retryRun method',
)

# Keep the existing Speedtest desktop rendering, but centralize the user agent.
replace_regex(
    r'        s\.setUserAgentString\(\n            "Mozilla/5\.0 \(Windows NT 10\.0; Win64; x64\) " \+\n            "AppleWebKit/537\.36 \(KHTML, like Gecko\) " \+\n            "Chrome/120\.0\.0\.0 Safari/537\.36"\);',
    '        s.setUserAgentString(SPEEDTEST_USER_AGENT);',
    'WebView initial Speedtest user agent',
)

# Result navigation only completes Speedtest; it never uploads before nPerf.
replace_regex(
    r'            @Override\n            public void onPageFinished\(WebView view, String url\) \{.*?\n            \}\n\n            @Override\n            public void onReceivedError\(WebView view, int errorCode,',
    '''            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                if ("speedtest".equals(phase) && url.contains("/result/") && !saved.get()) {
                    handler.postDelayed(() -> completeSpeedtestFromUrl(url), 4000);
                } else if ("nperf".equals(phase) && url.contains("/result") && !nSaved.get()) {
                    if (nSaved.compareAndSet(false, true))
                        handler.postDelayed(MainActivity.this::extractNperfMetrics, 4000);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,''',
    'onPageFinished method',
)

# Both WebView error callbacks now use the same phase-aware handler.
replace_regex(
    r'            @Override\n            public void onReceivedError\(WebView view, int errorCode,.*?\n            \}\n        \}\);',
    '''            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                handleWebViewError();
            }

            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    handleWebViewError();
                }
            }
        });''',
    'WebView error callbacks',
)

# URL polling hands control to the one-shot Speedtest completion guard.
replace_regex(
    r'                // PASO 1: Monitorear URL — funciona en fondo y primer plano\n.*?\n                // PASO 2: JS solo en primer plano',
    '''                // PASO 1: Monitorear URL — funciona en fondo y primer plano
                String curUrl = webView.getUrl();
                if (curUrl != null && curUrl.contains("/result/")) {
                    completeSpeedtestFromUrl(curUrl);
                    return;
                }

                // PASO 2: JS solo en primer plano''',
    'Speedtest URL polling completion',
)

replace_regex(
    r'    private void extractMetricsThenSave\(\) \{.*?\n    \}\n\n    // ═+\n    // PROCESAR DATOS',
    '''    private void extractMetricsThenStartNperf() {
        if (webView == null) { transitionToNperf(); return; }
        webView.evaluateJavascript(
            "(function(){" +
            "  function g(ss){for(var i=0;i<ss.length;i++){" +
            "    var els=document.querySelectorAll(ss[i]);" +
            "    for(var j=0;j<els.length;j++){" +
            "      var v=els[j].textContent||'';" +
            "      v=v.trim().replace(/[^0-9.]/g,'');" +
            "      var n=parseFloat(v);if(!isNaN(n)&&n>0)return ''+n;" +
            "    }}return '';}" +
            "  return JSON.stringify({" +
            "    dl:g(['[data-download-speed]','.download-speed','#download-value'])," +
            "    ul:g(['[data-upload-speed]','.upload-speed','#upload-value'])," +
            "    pg:g(['[data-latency]','.ping-speed','#ping-value'])," +
            "    jt:g(['[data-jitter]','.jitter-speed','#jitter-value'])" +
            "  });" +
            "})()",
            value -> {
                if (value != null && !value.equals("null")) {
                    try {
                        String v = value.replaceAll("^\\\"|\\\"$","");
                        String dl = key(v,"dl"), ul = key(v,"ul");
                        String pg = key(v,"pg"), jt = key(v,"jt");
                        if (!dl.isEmpty()) download = dl;
                        if (!ul.isEmpty()) upload   = ul;
                        if (!pg.isEmpty()) ping     = pg;
                        if (!jt.isEmpty()) jitter   = jt;
                    } catch (Exception e) { e.printStackTrace(); }
                }
                transitionToNperf();
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROCESAR DATOS''',
    'Speedtest final metric extraction',
)

# DOM completion updates values, then delegates to the same one-shot completion method.
replace_regex(
    r'            if \(ready && saved\.compareAndSet\(false, true\)\) \{.*?\n            \}\n        \} catch',
    '''            if (ready) {
                resultId  = rid.isEmpty()  ? resultId  : rid;
                resultUrl = url == null    ? resultUrl : url;
                download  = hasDl ? dl : download;
                upload    = hasUl ? ul : upload;
                ping = pg; jitter = jt;
                completeSpeedtest();
            }
        } catch''',
    'processData completion block',
)

# A combined result can be uploaded only once, after nPerf has completed.
replace_literal(
    '    private void saveTxt() {\n        setStatus("Guardando resultado en Google Drive...");\n',
    '    private void saveTxt() {\n'
    '        if (!"nperf".equals(phase) || !nSaved.get()) return;\n'
    '        if (!finalSaveStarted.compareAndSet(false, true)) return;\n'
    '        setStatus("Guardando resultado en Google Drive...");\n',
    'final save guard',
)

# nPerf keeps its current implementation for this phase, but uses a centralized UA and URL.
replace_regex(
    r'    private void startNperf\(\) \{.*?\n    \}\n\n    // ── Presionar "Iniciar test" en nperf',
    '''    private void startNperf() {
        if (!isWifiConnected()) { showNoWifiDialog(); return; }
        if (!isConnected())     { showNoInternetDialog(); return; }

        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = "";
        nSaved.set(false); nErrorDetected.set(false);

        setStatus("Cargando nperf.com...");
        progressBar.setVisibility(View.VISIBLE);

        clearWebViewSession(true);
        setNperfUserAgent();
        webView.loadUrl(NPERF_URL);
        handler.postDelayed(() -> {
            tvResultId.setText("nperf — midiendo...");
            tvDownload.setText("-");
            tvUpload.setText("-");
            tvPing.setText("-");
            tvJitter.setText("-");
            layoutResults.setVisibility(View.VISIBLE);
        }, 500);
    }

    // ── Presionar "Iniciar test" en nperf''',
    'startNperf method',
)

# Timeout and nPerf errors now follow the configured retry limit.
replace_literal(
    '''                if (!nSaved.get() && nPollCount >= MAX_POLL) {
                    // Timeout — guardar con lo que haya
                    if (nSaved.compareAndSet(false, true))
                        handler.post(() -> saveTxt());
                    return;
                }
''',
    '''                if (!nSaved.get() && nPollCount >= MAX_POLL) {
                    setStatus("Tiempo agotado en nperf. Reintentando...");
                    retryNperf();
                    return;
                }
''',
    'nPerf timeout handling',
)

replace_literal(
    '                        handler.postDelayed(this::startNperf, 5000);\n',
    '                        handler.postDelayed(this::retryNperf, 5000);\n',
    'nPerf DOM error retry',
)

# Once the combined file is uploaded, only scheduling/final UI remains.
replace_regex(
    r'    private void onRunComplete\(boolean success\) \{.*?\n    \}\n\n    // ═+\n    // UTILIDADES',
    '''    private void onRunComplete(boolean success) {
        progressBar.setVisibility(View.GONE);
        showPanel();

        if (currentRun < totalRuns) {
            String msg = "Prueba " + currentRun +
                (success ? " guardada." : " fallida.") +
                " Siguiente en " + waitBetween + "s...";
            setStatus(msg);
            SpeedtestService.update(this, msg,
                "Prueba " + currentRun + " de " + totalRuns);
            Toast.makeText(this,
                "Prueba " + currentRun +
                (success ? " guardada." : " con error de guardado.") +
                " Siguiente en " + waitBetween + "s...",
                Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::startRun, waitBetween * 1000L);
        } else {
            isRunning = false;
            stopBannerWatcher();
            releaseWakeLock();
            SpeedtestService.stop(this);
            setStatus(success
                ? "COMPLETADO: " + totalRuns + " pruebas guardadas"
                : "COMPLETADO con error al guardar la ultima prueba");
            Toast.makeText(this,
                "Todas las pruebas completadas (" + totalRuns + ")",
                Toast.LENGTH_LONG).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILIDADES''',
    'onRunComplete method',
)

# Insert phase coordination helpers immediately before the utilities section.
replace_literal(
    '''    // ══════════════════════════════════════════════════════════════════════
    // UTILIDADES
''',
    '''    private void setSpeedtestUserAgent() {
        if (webView != null) webView.getSettings().setUserAgentString(SPEEDTEST_USER_AGENT);
    }

    private void setNperfUserAgent() {
        if (webView != null) webView.getSettings().setUserAgentString(NPERF_USER_AGENT);
    }

    private void clearWebViewSession(boolean acceptCookies) {
        if (webView == null) return;
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(acceptCookies);
        cm.removeAllCookies(null);
        cm.flush();
    }

    private void reloadSpeedtestCurrentAttempt() {
        if (webView == null) return;
        phase = "speedtest";
        pageLoaded = false;
        goPressed = false;
        pollCount = 0;
        saved.set(false);
        errorDetected.set(false);
        setSpeedtestUserAgent();
        clearWebViewSession(false);
        webView.loadUrl(SPEEDTEST_URL);
        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
    }

    private void completeSpeedtestFromUrl(String url) {
        if (url != null) {
            Matcher matcher = Pattern.compile("result/([\\w-]+)").matcher(url);
            if (matcher.find()) resultId = matcher.group(1);
            resultUrl = url;
        }
        completeSpeedtest();
    }

    private void completeSpeedtest() {
        if (!"speedtest".equals(phase) || !saved.compareAndSet(false, true)) return;
        handler.post(() -> {
            showPanel();
            if (!isInBackground && webView != null) {
                extractMetricsThenStartNperf();
            } else {
                transitionToNperf();
            }
        });
    }

    private void transitionToNperf() {
        if (!nperfTransitionStarted.compareAndSet(false, true)) return;
        phase = "nperf";
        nperfRetry = 0;
        setStatus("Speedtest OK. Iniciando nperf...");
        SpeedtestService.update(this,
            "Iniciando nperf - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::startNperf, 2000);
    }

    private void resumeCurrentPhaseAfterConnection() {
        if ("nperf".equals(phase)) {
            nErrorDetected.set(false);
            handler.postDelayed(this::startNperf, 1000);
        } else {
            errorDetected.set(false);
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 1000);
        }
    }

    private void retryNperf() {
        if (nSaved.get() || finalSaveStarted.get()) return;
        if (nperfRetry < maxRetries) {
            nperfRetry++;
            nErrorDetected.set(false);
            setStatus("Reintentando nperf (" + nperfRetry + "/" + maxRetries + ")...");
            SpeedtestService.update(this,
                "Reintentando nperf - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);
            handler.postDelayed(this::startNperf, 3000);
        } else {
            showErrorDialog();
        }
    }

    private void handleWebViewError() {
        boolean nperfPhase = "nperf".equals(phase);
        AtomicBoolean completed = nperfPhase ? nSaved : saved;
        AtomicBoolean errorFlag = nperfPhase ? nErrorDetected : errorDetected;
        if (completed.get() || !errorFlag.compareAndSet(false, true)) return;

        handler.post(() -> {
            if (!isWifiConnected()) {
                showNoWifiDialog();
            } else if (!isConnected()) {
                showNoInternetDialog();
            } else {
                setStatus("Error de red - reintentando...");
                if (nperfPhase) retryNperf();
                else retryRun();
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILIDADES
''',
    'phase coordination helpers',
)

# Safety checks: old premature-save paths must be gone.
for forbidden in (
    'extractMetricsThenSave',
    'handler.postDelayed(MainActivity.this::saveTxt, 2000);\n                });\n            }\n        } catch',
    'if (phase.equals("speedtest")) {\n            // Speedtest terminó',
):
    if forbidden in text:
        raise RuntimeError(f"forbidden legacy flow remains: {forbidden[:60]}")

required = (
    'private void transitionToNperf()',
    'private void retryNperf()',
    'private void completeSpeedtest()',
    'if (!finalSaveStarted.compareAndSet(false, true)) return;',
    'webView.loadUrl(SPEEDTEST_URL);',
    'webView.loadUrl(NPERF_URL);',
)
for marker in required:
    if text.count(marker) != 1:
        raise RuntimeError(f"required marker count invalid for {marker!r}: {text.count(marker)}")

if text == original:
    raise RuntimeError("migration produced no changes")

SOURCE.write_text(text, encoding="utf-8")
print(f"Phase 2 migration applied: {len(original.splitlines())} -> {len(text.splitlines())} lines")
