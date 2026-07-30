#!/usr/bin/env python3
from pathlib import Path

main_path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
main = main_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


main = replace_once(
    main,
    "import androidx.appcompat.app.AppCompatActivity;\n"
    "import androidx.core.app.ActivityCompat;",
    "import androidx.appcompat.app.AppCompatActivity;\n"
    "import androidx.activity.result.ActivityResult;\n"
    "import androidx.activity.result.ActivityResultLauncher;\n"
    "import androidx.activity.result.contract.ActivityResultContracts;\n"
    "import androidx.core.app.ActivityCompat;",
    "activity result imports",
)

main = replace_once(
    main,
    "    private final Handler handler = new Handler(Looper.getMainLooper());\n"
    "    private NperfAutomation nperfAutomation;",
    "    private final Handler handler = new Handler(Looper.getMainLooper());\n"
    "    private ActivityResultLauncher<Intent> nperfBrowserLauncher;\n"
    "    private boolean nperfBrowserActive = false;\n"
    "    private NperfAutomation nperfAutomation;",
    "browser launcher fields",
)

main = replace_once(
    main,
    "        setContentView(R.layout.activity_main);\n\n"
    "        tvStatus",
    "        setContentView(R.layout.activity_main);\n\n"
    "        nperfBrowserLauncher = registerForActivityResult(\n"
    "            new ActivityResultContracts.StartActivityForResult(),\n"
    "            this::handleNperfBrowserResult);\n\n"
    "        tvStatus",
    "register Custom Tab result launcher",
)

main = replace_once(
    main,
    "        setupWebView();\n"
    "        setupNperfAutomation();\n"
    "        startForegroundService();",
    "        setupWebView();\n"
    "        startForegroundService();",
    "disable WebView nPerf controller",
)

main = replace_once(
    main,
    "        nperfRetry = 0;\n"
    "        nperfCompatibilityAttempt = 0;",
    "        nperfRetry = 0;\n"
    "        nperfBrowserActive = false;\n"
    "        NperfBrowserCoordinator.cancel(this, null);\n"
    "        nperfCompatibilityAttempt = 0;",
    "reset browser session",
)

main = replace_once(
    main,
    "        handler.postDelayed(this::startNperf, 2000);",
    "        handler.postDelayed(this::startNperfBrowser, 1200);",
    "transition to Custom Tab",
)

main = replace_once(
    main,
    "            handler.postDelayed(this::startNperf, 1000);",
    "            handler.postDelayed(this::startNperfBrowser, 1000);",
    "resume nPerf Custom Tab",
)

main = replace_once(
    main,
    "            handler.postDelayed(this::startNperf, 3000);",
    "            handler.postDelayed(this::startNperfBrowser, 1500);",
    "retry nPerf Custom Tab",
)

marker = "    private void setupNperfAutomation() {\n"
if main.count(marker) != 1:
    raise RuntimeError("setupNperfAutomation marker not found exactly once")

methods = '''    // ══════════════════════════════════════════════════════════════════════
    // NPERF EN CUSTOM TAB — navegador completo + AccessibilityService
    // ══════════════════════════════════════════════════════════════════════
    private void startNperfBrowser() {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (nperfBrowserActive) return;
        if (!isWifiConnected()) {
            showNoWifiDialog(this::startNperfBrowser);
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog(this::startNperfBrowser);
            return;
        }

        nperfBrowserActive = true;
        nErrorDetected.set(false);
        setStatus("Speedtest OK. Abriendo nPerf en navegador completo...");
        SpeedtestService.update(this,
            "Abriendo nPerf en navegador - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        Intent intent = new Intent(this, NperfBrowserActivity.class)
            .putExtra(NperfBrowserActivity.EXTRA_RUN, currentRun)
            .putExtra(NperfBrowserActivity.EXTRA_TOTAL, totalRuns);
        nperfBrowserLauncher.launch(intent);
    }

    private void handleNperfBrowserResult(ActivityResult activityResult) {
        nperfBrowserActive = false;
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;

        Intent data = activityResult == null ? null : activityResult.getData();
        if (activityResult != null && activityResult.getResultCode() == RESULT_OK &&
                data != null) {
            nDownload = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_DOWNLOAD));
            nUpload = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_UPLOAD));
            nPing = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_LATENCY));
            nJitter = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_JITTER));
            nServer = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_SERVER));
            nOperator = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_OPERATOR));
            nResultId = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_RESULT_ID));
            nResultUrl = valueOrEmpty(data.getStringExtra(
                NperfBrowserCoordinator.EXTRA_RESULT_URL));

            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                showNperfBrowserDecision(
                    "INCOMPLETE_RESULT",
                    "nPerf devolvió un resultado sin descarga o subida");
                return;
            }

            if (nSaved.compareAndSet(false, true)) {
                nDone = true;
                progressBar.setVisibility(View.GONE);
                showPanel();
                setStatus("nPerf completo. Generando TXT combinado...");
                SpeedtestService.update(this,
                    "nPerf completo - guardando prueba " + currentRun,
                    "Prueba " + currentRun + " de " + totalRuns);
                handler.postDelayed(this::saveTxt, 700L);
            }
            return;
        }

        String code = data == null ? "NPERF_BROWSER_CANCELLED" :
            valueOrEmpty(data.getStringExtra(NperfBrowserCoordinator.EXTRA_STATE));
        String detail = data == null ? "nPerf terminó sin devolver resultados" :
            valueOrEmpty(data.getStringExtra(NperfBrowserCoordinator.EXTRA_DETAIL));
        if (detail.isEmpty()) detail = "nPerf no devolvió un resultado completo";
        showNperfBrowserDecision(code, detail);
    }

    private void showNperfBrowserDecision(String code, String detail) {
        String safeCode = valueOrEmpty(code);
        String safeDetail = valueOrEmpty(detail);
        if (safeCode.isEmpty()) safeCode = "NPERF_BROWSER_ERROR";
        if (safeDetail.isEmpty()) safeDetail = "nPerf no completó la prueba";

        setStatus("Error nPerf: " + safeDetail);
        SpeedtestService.update(this,
            "Error nPerf " + safeCode + " - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        final String message = safeDetail;
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("nPerf no completó la prueba")
            .setMessage(
                message + "\n\n" +
                "Los resultados de Speedtest se conservan. Puede reintentar " +
                "únicamente nPerf o detener el proceso. No se generará un TXT incompleto.")
            .setPositiveButton("Reintentar nPerf", (dialog, which) -> {
                nErrorDetected.set(false);
                nperfBrowserActive = false;
                setStatus("Reintentando únicamente nPerf...");
                handler.postDelayed(this::startNperfBrowser, 700L);
            })
            .setNegativeButton("Detener", (dialog, which) -> {
                NperfBrowserCoordinator.cancel(this, null);
                isRunning = false;
                releaseWakeLock();
                SpeedtestService.stop(this);
                setStatus("Proceso detenido: nPerf no completó la prueba " + currentRun);
            })
            .setCancelable(false)
            .show());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

'''
main = main.replace(marker, methods + marker, 1)

required_main = (
    "private void startNperfBrowser()",
    "this::handleNperfBrowserResult",
    "handler.postDelayed(this::startNperfBrowser, 1200)",
    "nPerf completo. Generando TXT combinado",
    "No se generará un TXT incompleto",
)
for value in required_main:
    if value not in main:
        raise RuntimeError(f"missing MainActivity marker: {value}")

main_path.write_text(main, encoding="utf-8")

browser_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserActivity.java")
browser = browser_path.read_text(encoding="utf-8")

browser = replace_once(
    browser,
    "import android.os.Bundle;\n"
    "import android.provider.Settings;",
    "import android.os.Bundle;\n"
    "import android.os.Handler;\n"
    "import android.os.Looper;\n"
    "import android.provider.Settings;",
    "browser handler imports",
)

browser = replace_once(
    browser,
    "    private TextView statusView;\n"
    "    private ProgressBar progressBar;",
    "    private final Handler handler = new Handler(Looper.getMainLooper());\n"
    "    private TextView statusView;\n"
    "    private ProgressBar progressBar;",
    "browser handler field",
)

browser = replace_once(
    browser,
    "        if (isAccessibilityServiceEnabled()) {\n"
    "            setupDialogVisible = false;\n"
    "            if (!browserLaunched) beginAndLaunch();\n"
    "        } else if (!setupDialogVisible) {",
    "        if (isAccessibilityServiceEnabled()) {\n"
    "            setupDialogVisible = false;\n"
    "            if (!browserLaunched) {\n"
    "                beginAndLaunch();\n"
    "            } else if (NperfBrowserCoordinator.isActiveToken(this, token)) {\n"
    "                // onResume after launch means the user/browser closed the tab.\n"
    "                // Wait briefly so a result broadcast can win the race.\n"
    "                handler.postDelayed(() -> {\n"
    "                    if (!isFinishing() &&\n"
    "                            NperfBrowserCoordinator.isActiveToken(this, token)) {\n"
    "                        NperfBrowserCoordinator.fail(this, token,\n"
    "                            \"TAB_CLOSED\",\n"
    "                            \"La pestaña de nPerf se cerró antes de completar la prueba\");\n"
    "                    }\n"
    "                }, 1400L);\n"
    "            }\n"
    "        } else if (!setupDialogVisible) {",
    "detect manually closed Custom Tab",
)

browser_path.write_text(browser, encoding="utf-8")
