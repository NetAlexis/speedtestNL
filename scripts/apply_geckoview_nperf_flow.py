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
    "    private NperfAutomation nperfAutomation;\n"
    "    private int nperfPollingSession = 0;",
    "    private NperfAutomation nperfAutomation;\n"
    "    private int nperfPollingSession = 0;\n"
    "    private boolean nperfGeckoActive = false;",
    "Gecko activity state field",
)

replace_once(
    "    private static final int PERM_REQ       = 100;\n"
    "    private static final int PERM_REQ_NOTIF = 101;",
    "    private static final int PERM_REQ          = 100;\n"
    "    private static final int PERM_REQ_NOTIF    = 101;\n"
    "    private static final int PERM_REQ_LOCATION = 102;\n"
    "    private static final int NPERF_GECKO_REQUEST = 202;",
    "request constants",
)

replace_once(
    "        nperfRetry = 0;\n"
    "        nperfCompatibilityAttempt = 0;",
    "        nperfRetry = 0;\n"
    "        nperfGeckoActive = false;\n"
    "        nperfCompatibilityAttempt = 0;",
    "reset Gecko activity state",
)

replace_once(
    "        handler.postDelayed(this::startNperf, 2000);",
    "        handler.postDelayed(this::startNperfGecko, 1200);",
    "Speedtest to GeckoView transition",
)

replace_once(
    "            handler.postDelayed(this::startNperf, 1000);",
    "            handler.postDelayed(this::startNperfGecko, 1000);",
    "resume GeckoView after connection",
)

replace_once(
    "            handler.postDelayed(this::startNperf, 3000);",
    "            handler.postDelayed(this::startNperfGecko, 3000);",
    "retry GeckoView nPerf",
)

old_progress = '''                    } else if (phase.equals("nperf") && !nPageLoaded && !nGoPressed) {
                        nPageLoaded = true;
                        handler.postDelayed(MainActivity.this::pressNperfGo, 2500);
                    }
'''
new_progress = '''                    }
'''
replace_once(old_progress, new_progress, "disable legacy WebView nPerf progress trigger")

request_marker = '''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERM_REQ_NOTIF);
            }
        }
'''
request_replacement = request_marker + '''        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERM_REQ_LOCATION);
        }
'''
replace_once(request_marker, request_replacement, "proactive location request")

insert_marker = "    private void setupNperfAutomation() {\n"
if text.count(insert_marker) != 1:
    raise RuntimeError("setupNperfAutomation marker missing or duplicated")

methods = r'''    // ══════════════════════════════════════════════════════════════════════
    // NPERF EN GECKOVIEW — motor Firefox integrado
    // ══════════════════════════════════════════════════════════════════════
    private void startNperfGecko() {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (nperfGeckoActive) return;
        if (!isWifiConnected()) { showNoWifiDialog(this::startNperfGecko); return; }
        if (!isConnected()) { showNoInternetDialog(this::startNperfGecko); return; }

        nperfGeckoActive = true;
        nErrorDetected.set(false);
        nPageLoaded = true;
        nGoPressed = true;
        nPollCount = 0;
        nperfPollingSession++;
        nperfPollingStarted.set(false);
        if (nperfAutomation != null) nperfAutomation.cancel();
        if (webView != null) webView.stopLoading();

        setStatus("Abriendo nPerf en GeckoView...");
        SpeedtestService.update(this,
            "nPerf GeckoView - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        Intent intent = new Intent(this, NperfGeckoActivity.class);
        intent.putExtra(NperfGeckoActivity.EXTRA_RUN, currentRun);
        intent.putExtra(NperfGeckoActivity.EXTRA_TOTAL_RUNS, totalRuns);
        // El sitio nPerf recibe permiso automático dentro de GeckoView. Android
        // muestra su diálogo del sistema una sola vez cuando aún no fue concedido.
        intent.putExtra(NperfGeckoActivity.EXTRA_LOCATION_MODE, "auto");
        startActivityForResult(intent, NPERF_GECKO_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != NPERF_GECKO_REQUEST) return;

        nperfGeckoActive = false;
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;

        if (resultCode == RESULT_OK && data != null) {
            nDownload = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_DOWNLOAD));
            nUpload = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_UPLOAD));
            nPing = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_LATENCY));
            nJitter = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_JITTER));
            nServer = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_SERVER));
            nOperator = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_OPERATOR));
            nResultId = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_RESULT_ID));
            nResultUrl = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_RESULT_URL));

            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                setStatus("nPerf GeckoView devolvió un resultado incompleto.");
                handler.postDelayed(this::retryNperf, 1200L);
                return;
            }

            if (nSaved.compareAndSet(false, true)) {
                nDone = true;
                progressBar.setVisibility(View.GONE);
                showPanel();
                setStatus("nPerf GeckoView completado. Guardando...");
                SpeedtestService.update(this,
                    "nPerf completado - prueba " + currentRun,
                    "Prueba " + currentRun + " de " + totalRuns);
                handler.postDelayed(this::saveTxt, 1000L);
            }
            return;
        }

        String code = data == null ? "GECKO_CANCELLED" :
            valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_ERROR_CODE));
        String detail = data == null ? "La actividad nPerf terminó sin resultado" :
            valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_ERROR_DETAIL));
        if (detail.isEmpty()) detail = "nPerf no devolvió un resultado válido";

        setStatus("Error nPerf GeckoView: " + detail);
        SpeedtestService.update(this,
            "Error nPerf GeckoView " + code,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::retryNperf, 1500L);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

'''
text = text.replace(insert_marker, methods + insert_marker, 1)

required = (
    "private void startNperfGecko()",
    "NPERF_GECKO_REQUEST = 202",
    "handler.postDelayed(this::startNperfGecko, 1200)",
    "NperfGeckoActivity.EXTRA_DOWNLOAD",
    "PERM_REQ_LOCATION",
)
for marker in required:
    if marker not in text:
        raise RuntimeError(f"missing marker: {marker}")

path.write_text(text, encoding="utf-8")
