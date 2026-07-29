#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import androidx.core.content.ContextCompat;\n",
    "import androidx.core.content.ContextCompat;\n\n"
    "import com.netlife.speedtestnl.nperf.NperfEngine;\n"
    "import com.netlife.speedtestnl.nperf.NperfEngineLoader;\n",
    "native nPerf imports",
)

replace_once(
    "    private NperfAutomation nperfAutomation;\n"
    "    private int nperfPollingSession = 0;",
    "    private NperfAutomation nperfAutomation;\n"
    "    private NperfEngine nperfEngine;\n"
    "    private int nperfPollingSession = 0;",
    "native nPerf field",
)

replace_once(
    "        setupWebView();\n"
    "        setupNperfAutomation();\n"
    "        startForegroundService();",
    "        setupWebView();\n"
    "        nperfEngine = NperfEngineLoader.load(this);\n"
    "        startForegroundService();",
    "load native nPerf engine",
)

replace_once(
    "    protected void onDestroy() {\n"
    "        if (nperfAutomation != null) nperfAutomation.cancel();\n"
    "        nperfPollingSession++;\n"
    "        super.onDestroy();",
    "    protected void onDestroy() {\n"
    "        if (nperfAutomation != null) nperfAutomation.cancel();\n"
    "        if (nperfEngine != null) {\n"
    "            nperfEngine.cancel();\n"
    "            nperfEngine.release();\n"
    "        }\n"
    "        nperfPollingSession++;\n"
    "        super.onDestroy();",
    "release native nPerf engine",
)

replace_once(
    "    private void resetState() {\n"
    "        resultId = \"\"; resultUrl = \"\"; download = \"\"; upload = \"\";",
    "    private void resetState() {\n"
    "        if (nperfEngine != null) nperfEngine.cancel();\n"
    "        resultId = \"\"; resultUrl = \"\"; download = \"\"; upload = \"\";",
    "cancel native engine on reset",
)

replace_once(
    "        handler.postDelayed(this::startNperf, 2000);",
    "        handler.postDelayed(this::startNperfNative, 1000);",
    "native transition after Speedtest",
)

replace_once(
    "            handler.postDelayed(this::startNperf, 1000);",
    "            handler.postDelayed(this::startNperfNative, 1000);",
    "native resume after connection",
)

replace_once(
    "            handler.postDelayed(this::startNperf, 3000);",
    "            handler.postDelayed(this::startNperfNative, 3000);",
    "native nPerf retry",
)

replace_once(
    "        setSpeedtestUserAgent();\n"
    "        clearWebViewSession(false);\n"
    "        webView.loadUrl(SPEEDTEST_URL);",
    "        setSpeedtestUserAgent();\n"
    "        clearWebViewSession(false);\n"
    "        webView.setVisibility(View.VISIBLE);\n"
    "        webView.loadUrl(SPEEDTEST_URL);",
    "restore WebView for Speedtest",
)

marker = "    private void setupNperfAutomation() {\n"
if text.count(marker) != 1:
    raise RuntimeError("setupNperfAutomation marker not found exactly once")

native_methods = r'''    // ══════════════════════════════════════════════════════════════════════
    // NPERF ENGINE SDK — flujo nativo (sin WebView)
    // ══════════════════════════════════════════════════════════════════════
    private void startNperfNative() {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (!isWifiConnected()) { showNoWifiDialog(this::startNperfNative); return; }
        if (!isConnected()) { showNoInternetDialog(this::startNperfNative); return; }

        if (nperfEngine == null) nperfEngine = NperfEngineLoader.load(this);
        if (!nperfEngine.isAvailable()) {
            showNperfSdkUnavailable(nperfEngine.getUnavailableReason());
            return;
        }

        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        nperfPollingStarted.set(false);
        nErrorDetected.set(false);
        nSaved.set(false);
        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = ""; nResultId = ""; nResultUrl = "";

        if (webView != null) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
        }
        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.VISIBLE);
        tvResultId.setText("nPerf SDK — preparando...");
        tvDownload.setText("-");
        tvUpload.setText("-");
        tvPing.setText("-");
        tvJitter.setText("-");
        setStatus("Iniciando nPerf Engine SDK...");
        SpeedtestService.update(this,
            "Iniciando nPerf SDK - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        NperfEngine.Request request = new NperfEngine.Request(
            currentRun,
            totalRuns,
            360,
            true,
            "SpeedtestNL-run-" + currentRun
        );

        nperfEngine.start(request, new NperfEngine.Listener() {
            @Override
            public void onState(NperfEngine.State state, String message) {
                handler.post(() -> {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    String detail = message == null || message.trim().isEmpty()
                        ? nativeNperfStateLabel(state) : message.trim();
                    String status = "nPerf SDK: " + detail;
                    setStatus(status);
                    SpeedtestService.update(MainActivity.this, status,
                        "Prueba " + currentRun + " de " + totalRuns);
                });
            }

            @Override
            public void onMetric(NperfEngine.Metric metric, double value) {
                handler.post(() -> updateNativeNperfMetric(metric, value));
            }

            @Override
            public void onComplete(NperfEngine.Result result) {
                handler.post(() -> completeNativeNperf(result));
            }

            @Override
            public void onError(String code, String message, Throwable cause) {
                handler.post(() -> {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    String detail = message == null || message.trim().isEmpty()
                        ? "error no especificado" : message.trim();
                    setStatus("Error nPerf SDK: " + detail);
                    SpeedtestService.update(MainActivity.this,
                        "Error nPerf SDK - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    if ("SDK_NOT_AVAILABLE".equals(code)) {
                        showNperfSdkUnavailable(detail);
                    } else {
                        retryNperf();
                    }
                });
            }
        });
    }

    private String nativeNperfStateLabel(NperfEngine.State state) {
        if (state == null) return "procesando";
        switch (state) {
            case PREPARING: return "preparando motor";
            case SELECTING_SERVER: return "seleccionando servidor";
            case LATENCY: return "midiendo latencia";
            case DOWNLOAD: return "midiendo descarga";
            case UPLOAD: return "midiendo subida";
            case FINALIZING: return "finalizando resultado";
            default: return "procesando";
        }
    }

    private void updateNativeNperfMetric(NperfEngine.Metric metric, double value) {
        if (!"nperf".equals(phase) || nSaved.get() || metric == null ||
                Double.isNaN(value) || Double.isInfinite(value) || value < 0) return;
        String formatted = formatNativeMetric(value);
        switch (metric) {
            case DOWNLOAD_MBPS:
                nDownload = formatted;
                break;
            case UPLOAD_MBPS:
                nUpload = formatted;
                break;
            case LATENCY_MS:
                nPing = formatted;
                break;
            case JITTER_MS:
                nJitter = formatted;
                break;
        }
        showPanel();
    }

    private void completeNativeNperf(NperfEngine.Result result) {
        if (!"nperf".equals(phase) || result == null ||
                !nSaved.compareAndSet(false, true)) return;

        nDownload = formatNativeMetric(result.downloadMbps);
        nUpload = formatNativeMetric(result.uploadMbps);
        nPing = formatNativeMetric(result.latencyMs);
        nJitter = formatNativeMetric(result.jitterMs);
        nServer = result.server;
        nOperator = result.operator;
        nResultId = result.resultId;
        nResultUrl = result.resultUrl;

        progressBar.setVisibility(View.GONE);
        showPanel();
        setStatus("nPerf SDK completado. Guardando...");
        SpeedtestService.update(this,
            "nPerf SDK completado - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::saveTxt, 1000L);
    }

    private String formatNativeMetric(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) return "";
        String formatted = String.format(Locale.US, "%.2f", value);
        return formatted.replaceAll("\\.?0+$", "");
    }

    private void showNperfSdkUnavailable(String reason) {
        if (!"nperf".equals(phase)) return;
        if (nperfEngine != null) nperfEngine.cancel();
        progressBar.setVisibility(View.GONE);
        isRunning = false;
        releaseWakeLock();
        SpeedtestService.stop(this);

        String detail = reason == null || reason.trim().isEmpty()
            ? "Falta el SDK privado de nPerf." : reason.trim();
        setStatus("nPerf SDK pendiente: " + detail);

        new AlertDialog.Builder(this)
            .setTitle("nPerf Engine SDK requerido")
            .setMessage(
                "Speedtest terminó correctamente, pero la medición nPerf nativa " +
                "no puede ejecutarse hasta instalar el AAR autorizado y su " +
                "documentación de integración.\n\n" + detail +
                "\n\nNo se generó un resultado combinado incompleto.")
            .setPositiveButton("Entendido", null)
            .setCancelable(false)
            .show();
    }

'''
text = text.replace(marker, native_methods + marker, 1)

required = (
    "private void startNperfNative()",
    "nperfEngine = NperfEngineLoader.load(this)",
    "handler.postDelayed(this::startNperfNative, 1000)",
    "webView.setVisibility(View.GONE)",
    "No se generó un resultado combinado incompleto.",
)
for item in required:
    if item not in text:
        raise RuntimeError(f"missing required marker: {item}")

path.write_text(text, encoding="utf-8")
