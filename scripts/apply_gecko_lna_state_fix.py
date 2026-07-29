#!/usr/bin/env python3
from pathlib import Path
import json


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# Gecko runtime and site permissions.
activity_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfGeckoActivity.java")
activity = activity_path.read_text(encoding="utf-8")
activity = replace_once(
    activity,
    "                                .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)\n"
    "                                .consoleOutput(true)",
    "                                .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)\n"
    "                                .setLnaEnabled(false)\n"
    "                                .setLnaBlocking(false)\n"
    "                                .setLnaBlockTrackers(false)\n"
    "                                .consoleOutput(true)",
    "disable Gecko local-network blocking for nPerf runtime",
)

permission_marker = '''                if (permission.permission == PERMISSION_PERSISTENT_STORAGE ||
                        permission.permission == PERMISSION_STORAGE_ACCESS ||
                        permission.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }
'''
permission_replacement = '''                if (permission.permission == PERMISSION_LOCAL_NETWORK_ACCESS ||
                        permission.permission == PERMISSION_LOCAL_DEVICE_ACCESS) {
                    setStatus("Acceso al servidor local nPerf: autorizado automáticamente");
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }

''' + permission_marker
activity = replace_once(
    activity,
    permission_marker,
    permission_replacement,
    "allow nPerf local-network content permissions",
)
activity_path.write_text(activity, encoding="utf-8")

# Extension state machine.
controller_path = Path("app/src/main/assets/nperf_automation/frame_controller.js")
controller = controller_path.read_text(encoding="utf-8")
controller = replace_once(
    controller,
    "  let firstActivationAt = 0;\n"
    "  let lastActivationAt = 0;\n"
    "  let consentAttempts = 0;",
    "  let firstActivationAt = 0;\n"
    "  let lastActivationAt = 0;\n"
    "  let firstMetricAt = 0;\n"
    "  let lastProgressAt = 0;\n"
    "  let testStarted = false;\n"
    "  let consentAttempts = 0;",
    "add nPerf measurement state fields",
)

text_marker = '''  const textOf = node => norm(node && (
    node.innerText || node.textContent || node.value ||
    node.getAttribute?.("aria-label") || node.getAttribute?.("title") || ""
  ));
'''
text_replacement = text_marker + '''
  const deepRawText = () => rootList().map(root => {
    try {
      if (root === document) {
        return document.body?.innerText || document.documentElement?.textContent || "";
      }
      return root.textContent || "";
    } catch (_) {
      return "";
    }
  }).join("\n");

  const deepText = () => norm(deepRawText());
'''
controller = replace_once(
    controller,
    text_marker,
    text_replacement,
    "add deep DOM and Shadow DOM text reader",
)
controller = replace_once(
    controller,
    "    const lines = (document.body?.innerText || \"\").split(/\\n+/).map(v => v.trim()).filter(Boolean);",
    "    const lines = deepRawText().split(/\\n+/).map(v => v.trim()).filter(Boolean);",
    "use deep text for metric extraction",
)
controller = replace_once(
    controller,
    '    if (["state", "tap", "metrics", "complete", "error"].includes(data.type)) {',
    '    if (["extension_ready", "state", "tap", "metrics", "complete", "error"].includes(data.type)) {',
    "mark child extension as active",
)

start = controller.index("  const tick = () => {")
end = controller.index("\n  send({ type: \"extension_ready\"", start)
new_tick = r'''  const failOnce = (code, detail, extra = {}) => {
    if (done) return;
    done = true;
    send(Object.assign({ type: "error", code, detail }, extra));
  };

  const tick = () => {
    if (done || !document.body) return;
    const now = Date.now();
    const body = deepText();

    const dataFailure = [
      "no se pueden recibir datos", "no se pudo recibir datos", "no se reciben datos",
      "compruebe su conexion a internet antes de iniciar un test",
      "cannot receive data", "unable to receive data", "no data received"
    ].find(value => body.includes(value));
    if (dataFailure) {
      return failOnce(
        "DATA_CHANNEL_FAILURE",
        "nPerf no pudo recibir datos del servidor de medición"
      );
    }

    const fatal = [
      "no fue posible inicializar", "no se pudo inicializar", "error al inicializar",
      "unable to initialize", "could not initialize", "initialization failed"
    ].find(value => body.includes(value));
    if (fatal) return failOnce("ENGINE_INITIALIZATION", fatal);

    const consent = findConsent(body);
    if (consent && consentAttempts < 6) {
      consentAttempts += 1;
      state("consent", `Aceptando cookies nPerf (${consentAttempts}/6)`, true);
      activate(consent, "consent");
      return;
    }

    const values = metrics();
    const hasThroughput = Boolean(values.download || values.upload);
    const hasLatency = Boolean(values.latency || values.jitter);
    const hasMetric = hasThroughput || hasLatency;

    if (hasMetric) {
      testStarted = true;
      if (!firstMetricAt) firstMetricAt = now;
      const signature = JSON.stringify(values);
      if (signature !== lastMetrics) {
        lastMetrics = signature;
        lastProgressAt = now;
        send(Object.assign({ type: "metrics" }, values));
      }
    }

    const completeText = [
      "haz click aqui para probar de nuevo", "haz clic aqui para probar de nuevo",
      "restart test", "reiniciar test", "reinitier le test"
    ].some(value => body.includes(value));
    if ((completeText || /\/r\/|\/result/i.test(location.href)) &&
        values.download && values.upload) {
      done = true;
      state("complete", "Resultado nPerf detectado", true);
      send(Object.assign({ type: "complete" }, values));
      return;
    }

    // A latency value alone is not a healthy speed test. If throughput never
    // begins, fail deterministically instead of displaying "midiendo" forever.
    if (hasLatency && !hasThroughput) {
      const latencyWait = now - (firstMetricAt || firstActivationAt || now);
      if (latencyWait >= 25000) {
        return failOnce(
          "DATA_CHANNEL_STALL",
          "nPerf obtuvo latencia, pero no inició descarga ni subida"
        );
      }
      state("latency", "nPerf midiendo latencia; esperando descarga");
      return;
    }

    if (hasThroughput) {
      state("running", "nPerf midiendo descarga y subida");
      return;
    }

    const startControl = findStart();
    const gauge = startControl ? null : findGauge();
    const target = startControl || gauge;

    // A deeper frame owns the operational meter. Parent frames must not click
    // their visual copy of the gauge while the child controller is active.
    if (target && now - childActiveAt < 6000) {
      state("child_active", "Medidor interno nPerf activo; esperando resultado");
      return;
    }

    if (target) {
      const role = startControl ? "start" : "gauge";

      if (firstActivationAt || testStarted) {
        const wait = now - firstActivationAt;
        if (wait >= 35000) {
          return failOnce(
            "START_DATA_TIMEOUT",
            "nPerf recibió la activación, pero no inició la transferencia de datos"
          );
        }
        state("connecting", "nPerf iniciado; esperando conexión con el servidor de medición");
        return;
      }

      // Exactly one activation per frame session. The native Android tap is
      // already sent together with the DOM activation; repeated taps corrupt
      // the nPerf state machine.
      if (activate(target, role)) {
        attempts = 1;
        testStarted = true;
        state("ready", `Activando Iniciar test (1/1, ${role})`, true);
      }
      return;
    }

    if (firstActivationAt) {
      const wait = now - firstActivationAt;
      if (wait >= 35000) {
        return failOnce(
          "START_DATA_TIMEOUT",
          "nPerf no inició datos después de activar el medidor"
        );
      }
      state("connecting", "nPerf iniciado; esperando conexión con el servidor de medición");
      return;
    }

    if ((body.includes("inicializando") || body.includes("initializing")) &&
        (!isTop || now - childActiveAt > 5000)) {
      state("initializing", "Inicializando motor y servidor nPerf");
      return;
    }

    if (now - beganAt > 90000) {
      return failOnce(
        "NO_OPERATIONAL_CONTROL",
        "nPerf no presentó un control operativo dentro de GeckoView",
        {
          frameCount: document.querySelectorAll("iframe,frame").length,
          excerpt: body.slice(0, 400)
        }
      );
    }

    if (!isTop || now - childActiveAt > 5000) {
      const count = document.querySelectorAll("iframe,frame").length;
      state("waiting", count
        ? `Buscando Iniciar test dentro de ${count} marco(s) nPerf`
        : "Esperando que nPerf prepare el medidor");
    }
  };
'''
controller = controller[:start] + new_tick + controller[end:]
controller_path.write_text(controller, encoding="utf-8")

# Versions.
manifest_path = Path("app/src/main/assets/nperf_automation/manifest.json")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["version"] = "1.4.0"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

gradle_path = Path("app/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode 3", "versionCode 4", "versionCode")
gradle = replace_once(
    gradle,
    'versionName "1.3-gecko-network"',
    'versionName "1.4-gecko-lna"',
    "versionName",
)
gradle_path.write_text(gradle, encoding="utf-8")

checks = {
    activity_path: [
        ".setLnaEnabled(false)",
        "PERMISSION_LOCAL_NETWORK_ACCESS",
        "Acceso al servidor local nPerf",
    ],
    controller_path: [
        "deepRawText",
        "DATA_CHANNEL_STALL",
        "Activando Iniciar test (1/1",
        "Medidor interno nPerf activo",
    ],
    gradle_path: ['versionName "1.4-gecko-lna"'],
}
for path, markers in checks.items():
    value = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in value:
            raise RuntimeError(f"missing marker {marker!r} in {path}")
