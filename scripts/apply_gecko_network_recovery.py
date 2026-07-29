#!/usr/bin/env python3
from pathlib import Path
import json


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# Gecko activity: permit nPerf data endpoints and perform one in-place retry.
activity_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfGeckoActivity.java")
activity = activity_path.read_text(encoding="utf-8")

activity = replace_once(
    activity,
    "    private boolean finished = false;\n    private int runNumber = 1;",
    "    private boolean finished = false;\n    private int controlledRetryCount = 0;\n    private int runNumber = 1;",
    "controlled retry field",
)

activity = replace_once(
    activity,
    "                                .javaScriptEnabled(true)\n                                .consoleOutput(true)",
    "                                .javaScriptEnabled(true)\n                                .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)\n                                .consoleOutput(true)",
    "allow nPerf insecure data endpoints",
)

activity = replace_once(
    activity,
    '''            case "error":
                fail(data.optString("code", "NPERF_ERROR"),
                    data.optString("detail", "nPerf informó un error"));
                break;
''',
    '''            case "error":
                handleExtensionError(
                    data.optString("code", "NPERF_ERROR"),
                    data.optString("detail", "nPerf informó un error")
                );
                break;
''',
    "route extension errors",
)

marker = "    private void updateMetrics(JSONObject data) {\n"
helpers = '''    private void handleExtensionError(String code, String detail) {
        if (finished) return;
        String safeCode = code == null ? "NPERF_ERROR" : code.trim();
        String safeDetail = detail == null || detail.trim().isEmpty()
            ? "nPerf informó un error" : detail.trim();

        boolean recoverable = "DATA_CHANNEL_FAILURE".equals(safeCode) ||
            "START_NOT_RESPONDING".equals(safeCode) ||
            "ENGINE_INITIALIZATION".equals(safeCode) ||
            "NO_OPERATIONAL_CONTROL".equals(safeCode);

        if (recoverable && controlledRetryCount < 1) {
            controlledRetryCount++;
            Log.w(TAG, safeCode + ": " + safeDetail +
                " — retrying nPerf inside GeckoView");
            setStatus("nPerf no recibió datos. Reintentando solo nPerf...");
            progressBar.setIndeterminate(true);
            handler.postDelayed(this::reloadNperfOnly, 1800L);
            return;
        }

        fail(safeCode, safeDetail);
    }

    private void reloadNperfOnly() {
        if (finished || session == null) return;
        download = "";
        upload = "";
        latency = "";
        jitter = "";
        server = "";
        operator = "";
        resultId = "";
        resultUrl = "";
        downloadView.setText("↓ -");
        uploadView.setText("↑ -");
        latencyView.setText("Ping -");
        jitterView.setText("Jitter -");
        setStatus("Recargando únicamente el medidor nPerf...");
        session.loadUri(NPERF_URL + "?stnl_retry=" + System.currentTimeMillis());
    }

'''
if marker not in activity:
    raise RuntimeError("updateMetrics marker missing")
activity = activity.replace(marker, helpers + marker, 1)
activity_path.write_text(activity, encoding="utf-8")

# Frame controller: detect the data-channel popup and stop rapid repeated taps.
controller_path = Path("app/src/main/assets/nperf_automation/frame_controller.js")
controller = controller_path.read_text(encoding="utf-8")

controller = replace_once(
    controller,
    "  let attempts = 0;\n  let consentAttempts = 0;",
    "  let attempts = 0;\n  let firstActivationAt = 0;\n  let lastActivationAt = 0;\n  let consentAttempts = 0;",
    "activation timing fields",
)

controller = replace_once(
    controller,
    "    lastTap = key;\n    lastTapAt = Date.now();",
    "    lastTap = key;\n    lastTapAt = Date.now();\n    if (role === \"start\" || role === \"gauge\") {\n      if (!firstActivationAt) firstActivationAt = lastTapAt;\n      lastActivationAt = lastTapAt;\n    }",
    "record start activation timing",
)

controller = replace_once(
    controller,
    '''    const fatal = ["no fue posible inicializar", "no se pudo inicializar", "error al inicializar",
      "unable to initialize", "could not initialize", "initialization failed"].find(v => body.includes(v));
    if (fatal) return send({ type: "error", code: "ENGINE_INITIALIZATION", detail: fatal });
''',
    '''    const dataFailure = [
      "no se pueden recibir datos", "no se pudo recibir datos", "no se reciben datos",
      "cannot receive data", "unable to receive data", "no data received"
    ].find(v => body.includes(v));
    if (dataFailure) {
      done = true;
      return send({
        type: "error",
        code: "DATA_CHANNEL_FAILURE",
        detail: "nPerf no pudo recibir datos del servidor de medición"
      });
    }

    const fatal = ["no fue posible inicializar", "no se pudo inicializar", "error al inicializar",
      "unable to initialize", "could not initialize", "initialization failed"].find(v => body.includes(v));
    if (fatal) {
      done = true;
      return send({ type: "error", code: "ENGINE_INITIALIZATION", detail: fatal });
    }
''',
    "detect nPerf data channel failure",
)

controller = replace_once(
    controller,
    '''    if ((start || gauge) && !hasMetric) {
      const target = start || gauge;
      const role = start ? "start" : "gauge";
      if (attempts >= 12) return send({ type: "error", code: "START_NOT_RESPONDING",
        detail: "El control Iniciar test siguió visible después de 12 activaciones" });
      if (activate(target, role)) {
        attempts += 1;
        state("ready", `Activando Iniciar test dentro del medidor (${attempts}/12, ${role})`, true);
      }
      return;
    }
''',
    '''    if ((start || gauge) && !hasMetric) {
      const now = Date.now();
      const target = start || gauge;
      const role = start ? "start" : "gauge";

      if (firstActivationAt && now - lastActivationAt < 15000) {
        state("connecting", "nPerf iniciado; esperando conexión con el servidor de medición");
        return;
      }

      if (attempts >= 3) {
        done = true;
        return send({
          type: "error",
          code: "START_NOT_RESPONDING",
          detail: "nPerf no inició después de 3 activaciones espaciadas"
        });
      }

      if (activate(target, role)) {
        attempts += 1;
        state("ready", `Activando Iniciar test (${attempts}/3, ${role})`, true);
      }
      return;
    }
''',
    "space and limit start activation",
)

controller_path.write_text(controller, encoding="utf-8")

# Extension and APK versions.
manifest_path = Path("app/src/main/assets/nperf_automation/manifest.json")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["version"] = "1.3.0"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

gradle_path = Path("app/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode 2", "versionCode 3", "versionCode")
gradle = replace_once(
    gradle,
    'versionName "1.2-gecko-frames"',
    'versionName "1.3-gecko-network"',
    "versionName",
)
gradle_path.write_text(gradle, encoding="utf-8")

# MainActivity: never restart automatically after GeckoView returns an error.
main_path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
main = main_path.read_text(encoding="utf-8")

main = replace_once(
    main,
    '''            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                setStatus("nPerf GeckoView devolvió un resultado incompleto.");
                handler.postDelayed(this::retryNperf, 1200L);
                return;
            }
''',
    '''            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                showNperfGeckoDecision(
                    "INCOMPLETE_RESULT",
                    "nPerf devolvió un resultado incompleto"
                );
                return;
            }
''',
    "incomplete Gecko result handling",
)

main = replace_once(
    main,
    '''        setStatus("Error nPerf GeckoView: " + detail);
        SpeedtestService.update(this,
            "Error nPerf GeckoView " + code,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::retryNperf, 1500L);
    }

    private String valueOrEmpty(String value) {
''',
    '''        showNperfGeckoDecision(code, detail);
    }

    private void showNperfGeckoDecision(String code, String detail) {
        String safeCode = code == null || code.trim().isEmpty()
            ? "NPERF_ERROR" : code.trim();
        String safeDetail = detail == null || detail.trim().isEmpty()
            ? "nPerf no devolvió un resultado válido" : detail.trim();

        setStatus("Error nPerf GeckoView: " + safeDetail);
        SpeedtestService.update(this,
            "Error nPerf GeckoView " + safeCode,
            "Prueba " + currentRun + " de " + totalRuns);

        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("nPerf no completó la prueba")
            .setMessage(
                safeDetail + "\n\n" +
                "Speedtest ya terminó y sus datos se conservan. " +
                "Puede reintentar únicamente nPerf o detener el proceso."
            )
            .setPositiveButton("Reintentar nPerf", (dialog, which) -> {
                nErrorDetected.set(false);
                setStatus("Reintentando únicamente nPerf...");
                handler.postDelayed(this::startNperfGecko, 700L);
            })
            .setNegativeButton("Detener", (dialog, which) -> {
                isRunning = false;
                releaseWakeLock();
                SpeedtestService.stop(this);
                setStatus("Proceso detenido: nPerf no completó la prueba " + currentRun);
            })
            .setCancelable(false)
            .show());
    }

    private String valueOrEmpty(String value) {
''',
    "manual Gecko failure decision",
)

main_path.write_text(main, encoding="utf-8")

# Guardrails.
checks = {
    activity_path: [
        "allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)",
        "private void handleExtensionError(String code, String detail)",
        "Reintentando solo nPerf",
    ],
    controller_path: [
        "DATA_CHANNEL_FAILURE",
        "esperando conexión con el servidor de medición",
        "attempts >= 3",
    ],
    main_path: [
        "private void showNperfGeckoDecision(String code, String detail)",
        "Reintentar únicamente nPerf",
    ],
}
for path, markers in checks.items():
    content = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in content:
            raise RuntimeError(f"missing marker {marker!r} in {path}")
