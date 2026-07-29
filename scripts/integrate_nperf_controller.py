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
    '    private static final String NPERF_URL = "https://www.nperf.com/es/";',
    '    private static final String NPERF_URL = "https://www.nperf.com/es/index";',
    "canonical nPerf URL",
)

replace_once(
    '    private final Handler handler = new Handler(Looper.getMainLooper());\n'
    '    private static final int PERM_REQ       = 100;',
    '    private final Handler handler = new Handler(Looper.getMainLooper());\n'
    '    private NperfAutomation nperfAutomation;\n'
    '    private int nperfPollingSession = 0;\n'
    '    private static final int PERM_REQ       = 100;',
    "nPerf controller fields",
)

replace_once(
    '        setupWebView();\n'
    '        startForegroundService();',
    '        setupWebView();\n'
    '        setupNperfAutomation();\n'
    '        startForegroundService();',
    "controller setup",
)

replace_once(
    '    protected void onDestroy() {\n'
    '        super.onDestroy();\n'
    '        releaseWakeLock();\n'
    '    }',
    '    protected void onDestroy() {\n'
    '        if (nperfAutomation != null) nperfAutomation.cancel();\n'
    '        nperfPollingSession++;\n'
    '        super.onDestroy();\n'
    '        releaseWakeLock();\n'
    '    }',
    "cancel nPerf on destroy",
)

replace_once(
    '                        handler.postDelayed(MainActivity.this::pressNperfGo, 8000);',
    '                        handler.postDelayed(MainActivity.this::pressNperfGo, 2500);',
    "faster nPerf initialization",
)

setup_method = '''    private void setupNperfAutomation() {
        nperfAutomation = new NperfAutomation(webView, handler,
            new NperfAutomation.Listener() {
                @Override
                public void onStatus(String message) {
                    if ("nperf".equals(phase) && !nSaved.get()) setStatus(message);
                }

                @Override
                public void onStartTouchSent() {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    setStatus("nperf: toque enviado; esperando respuesta...");
                    SpeedtestService.update(MainActivity.this,
                        "nperf esperando resultados - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                }

                @Override
                public void onManualStartAvailable() {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    setStatus("nperf no inició automáticamente. Puede tocar Iniciar test; no se recargará.");
                    SpeedtestService.update(MainActivity.this,
                        "nperf esperando inicio manual - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                }
            });
    }

'''
marker = '    // ══════════════════════════════════════════════════════════════════════\n    // NPERF — Iniciar prueba\n'
replace_once(marker, setup_method + marker, "nPerf controller setup method")

replace_once(
    '        nGoPressed = false; nPageLoaded = false; nPollCount = 0;\n'
    '        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";',
    '        if (nperfAutomation != null) nperfAutomation.cancel();\n'
    '        nperfPollingSession++;\n'
    '        nGoPressed = false; nPageLoaded = false; nPollCount = 0;\n'
    '        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";',
    "start fresh nPerf session",
)

press_pattern = re.compile(
    r'    // ── Presionar "Iniciar test" en nperf ─+\n'
    r'    private void pressNperfGo\(\) \{.*?\n'
    r'    private boolean isNperfResultUrl\(String url\)',
    re.DOTALL,
)
press_replacement = '''    // ── Presionar "Iniciar test" en nperf ─────────────────────────────────
    private void pressNperfGo() {
        if (nGoPressed || nSaved.get()) return;

        String curUrl = webView == null ? null : webView.getUrl();
        if (curUrl == null || !curUrl.contains("nperf.com")) {
            setStatus("nperf todavía cargando; esperando sin recargar...");
            handler.postDelayed(this::pressNperfGo, 1500);
            return;
        }

        nGoPressed = true;
        setStatus("Preparando automatización nperf...");
        if (nperfAutomation != null) {
            nperfAutomation.begin();
        } else {
            nGoPressed = false;
            setStatus("Controlador nperf no disponible.");
        }
    }

    private boolean isNperfResultUrl(String url)'''
text, count = press_pattern.subn(lambda _: press_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"replace legacy nPerf automation: expected 1 match, found {count}")

replace_once(
    '    private void startNperfPolling() {\n'
    '        if (!nperfPollingStarted.compareAndSet(false, true)) return;\n'
    '        handler.postDelayed(new Runnable() {',
    '    private void startNperfPolling() {\n'
    '        if (!nperfPollingStarted.compareAndSet(false, true)) return;\n'
    '        final int pollingSession = nperfPollingSession;\n'
    '        handler.postDelayed(new Runnable() {',
    "polling session token",
)

replace_once(
    '                if (nSaved.get()) return;\n'
    '                nPollCount++;',
    '                if (pollingSession != nperfPollingSession || nSaved.get()) return;\n'
    '                nPollCount++;',
    "ignore stale nPerf polling",
)

replace_once(
    '                if (!nSaved.get() && nPollCount >= MAX_POLL) {\n'
    '                    setStatus("Tiempo agotado en nperf. Reintentando...");\n'
    '                    retryNperf();\n'
    '                    return;\n'
    '                }\n'
    '                if (!nSaved.get()) handler.postDelayed(this, 3000);',
    '                if (!nSaved.get() && nPollCount == 40) {\n'
    '                    setStatus("nperf sigue sin resultados. Puede iniciar manualmente; no se recargará.");\n'
    '                }\n'
    '                if (!nSaved.get() && nPollCount >= MAX_POLL) {\n'
    '                    setStatus("nperf sin respuesta. La página se mantiene abierta para inicio manual.");\n'
    '                    handler.postDelayed(this, 8000);\n'
    '                    return;\n'
    '                }\n'
    '                if (!nSaved.get() && pollingSession == nperfPollingSession)\n'
    '                    handler.postDelayed(this, 3000);',
    "remove automatic nPerf timeout reload",
)

replace_once(
    '    private void reloadSpeedtestCurrentAttempt() {\n'
    '        if (webView == null) return;\n'
    '        phase = "speedtest";',
    '    private void reloadSpeedtestCurrentAttempt() {\n'
    '        if (webView == null) return;\n'
    '        if (nperfAutomation != null) nperfAutomation.cancel();\n'
    '        nperfPollingSession++;\n'
    '        phase = "speedtest";',
    "cancel nPerf when returning to Speedtest",
)

required = (
    'private NperfAutomation nperfAutomation;',
    'setupNperfAutomation();',
    'nperfAutomation.begin();',
    'final int pollingSession = nperfPollingSession;',
    'no se recargará',
    'https://www.nperf.com/es/index',
)
for value in required:
    if value not in text:
        raise RuntimeError(f"missing required marker: {value}")

for forbidden in (
    'private void dismissNperfConsentThenStart(',
    'private void attemptNperfStart(',
    'private void tapWebViewCssPoint(',
    'handler.postDelayed(this::retryNperf, 2000);',
):
    if forbidden in text:
        raise RuntimeError(f"legacy nPerf start code remains: {forbidden}")

path.write_text(text, encoding="utf-8")
