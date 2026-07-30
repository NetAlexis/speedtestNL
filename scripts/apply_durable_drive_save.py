#!/usr/bin/env python3
from pathlib import Path

main_path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = main_path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "    private boolean nperfBrowserActive = false;\n"
    "    private final AtomicBoolean speedtestResultExtractionStarted =",
    "    private boolean nperfBrowserActive = false;\n"
    "    private String pendingSaveFileName = \"\";\n"
    "    private String pendingSaveContent = \"\";\n"
    "    private boolean driveSaveDecisionVisible = false;\n"
    "    private final AtomicBoolean speedtestResultExtractionStarted =",
    "pending save fields",
)

replace_once(
    "        nperfTransitionStarted.set(false);\n"
    "        finalSaveStarted.set(false);\n",
    "        nperfTransitionStarted.set(false);\n"
    "        finalSaveStarted.set(false);\n"
    "        pendingSaveFileName = \"\";\n"
    "        pendingSaveContent = \"\";\n"
    "        driveSaveDecisionVisible = false;\n",
    "reset pending save state",
)

replace_once(
    "        new Thread(() -> {\n"
    "            boolean ok = uploadToDrive(fileName, finalTxt);\n"
    "            handler.post(() -> onRunComplete(ok));\n"
    "        }).start();",
    "        pendingSaveFileName = fileName;\n"
    "        pendingSaveContent = finalTxt;\n"
    "        beginPendingResultUpload(fileName, finalTxt);",
    "route save through durable manager",
)

start = text.find("    private boolean uploadToDrive(String fileName, String content) {")
end_marker = "    // ══════════════════════════════════════════════════════════════════════\n    // NPERF EN CUSTOM TAB"
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise RuntimeError("uploadToDrive method boundaries not found")

replacement = r'''    private void beginPendingResultUpload(String fileName, String content) {
        setStatus("Guardando copia local antes de subir a Drive...");
        SpeedtestService.update(this,
            "Guardando prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        ResultSaveManager.persistAndUpload(this, DRIVE_SCRIPT_URL,
            fileName, content, new ResultSaveManager.Callback() {
                @Override
                public void onStatus(String message) {
                    handler.post(() -> {
                        setStatus(message);
                        SpeedtestService.update(MainActivity.this, message,
                            "Prueba " + currentRun + " de " + totalRuns);
                    });
                }

                @Override
                public void onSuccess(File localFile) {
                    handler.post(() -> {
                        pendingSaveFileName = "";
                        pendingSaveContent = "";
                        driveSaveDecisionVisible = false;
                        setStatus("Guardado confirmado en Google Drive: " + fileName);
                        onRunComplete(true);
                    });
                }

                @Override
                public void onFailure(String detail, File localFile) {
                    handler.post(() -> {
                        finalSaveStarted.set(false);
                        String localPath = localFile == null ? "" : localFile.getAbsolutePath();
                        showDriveSaveDecision(detail, localPath);
                    });
                }
            });
    }

    private void showDriveSaveDecision(String detail, String localPath) {
        if (driveSaveDecisionVisible) return;
        driveSaveDecisionVisible = true;

        String safeDetail = valueOrEmpty(detail);
        if (safeDetail.isEmpty()) safeDetail = "Google Drive no confirmó la carga";
        String locationText = valueOrEmpty(localPath).isEmpty()
            ? "No se pudo confirmar una copia local."
            : "El TXT quedó protegido localmente y no se repetirá la medición.";

        setStatus("Resultado pendiente de Google Drive: " + safeDetail);
        SpeedtestService.update(this,
            "Resultado pendiente de Drive - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        final String message = safeDetail;
        new AlertDialog.Builder(this)
            .setTitle("No se confirmó el guardado en Drive")
            .setMessage(message + "\n\n" + locationText +
                "\n\nLa siguiente prueba no comenzará hasta confirmar esta carga.")
            .setPositiveButton("Reintentar subida", (dialog, which) -> {
                driveSaveDecisionVisible = false;
                if (pendingSaveFileName.isEmpty() || pendingSaveContent.isEmpty()) {
                    setStatus("No hay un resultado pendiente disponible para reintentar.");
                    return;
                }
                if (!finalSaveStarted.compareAndSet(false, true)) return;
                beginPendingResultUpload(pendingSaveFileName, pendingSaveContent);
            })
            .setNegativeButton("Detener", (dialog, which) -> {
                driveSaveDecisionVisible = false;
                isRunning = false;
                releaseWakeLock();
                SpeedtestService.stop(this);
                setStatus("Proceso detenido. El resultado pendiente no fue descartado.");
            })
            .setCancelable(false)
            .show();
    }

'''
text = text[:start] + replacement + text[end:]

replace_once(
    "    private void onRunComplete(boolean success) {\n"
    "        progressBar.setVisibility(View.GONE);\n"
    "        showPanel();\n\n"
    "        if (currentRun < totalRuns) {",
    "    private void onRunComplete(boolean success) {\n"
    "        progressBar.setVisibility(View.GONE);\n"
    "        showPanel();\n\n"
    "        if (!success) {\n"
    "            finalSaveStarted.set(false);\n"
    "            setStatus(\"La prueba no avanzará hasta guardar el resultado.\");\n"
    "            return;\n"
    "        }\n\n"
    "        if (currentRun < totalRuns) {",
    "block next run after save failure",
)

required = [
    "private void beginPendingResultUpload",
    "La siguiente prueba no comenzará hasta confirmar esta carga.",
    "ResultSaveManager.persistAndUpload",
    "pendingSaveFileName = fileName",
    "if (!success)",
]
for marker in required:
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

if "private boolean uploadToDrive" in text:
    raise RuntimeError("legacy uploadToDrive method still present")

main_path.write_text(text, encoding="utf-8")

build_path = Path("app/build.gradle")
build = build_path.read_text(encoding="utf-8")
if 'versionCode 12' not in build or 'versionName "2.2-nperf-start-escalation"' not in build:
    raise RuntimeError("unexpected build version")
build = build.replace('versionCode 12', 'versionCode 13', 1)
build = build.replace(
    'versionName "2.2-nperf-start-escalation"',
    'versionName "2.3-durable-drive-save"',
    1,
)
build_path.write_text(build, encoding="utf-8")
