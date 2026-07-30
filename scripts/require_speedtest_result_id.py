#!/usr/bin/env python3
from pathlib import Path

main_path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
gradle_path = Path("app/build.gradle")
main = main_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

main = replace_once(
    main,
    '''            if (hasStoredSpeedtestResult()) {
                setStatus("Speedtest verificado. Abriendo nPerf...");''',
    '''            if (hasStoredSpeedtestResult()) {
                setStatus("Speedtest verificado con Result ID " + resultId +
                    ". Abriendo nPerf...");''',
    "verified Speedtest status",
)

main = replace_once(
    main,
    '''                setStatus("Speedtest finalizó. Leyendo descarga, subida y ping (" +
                    speedtestResultExtractionAttempt + "/" +
                    MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS + ")...");''',
    '''                setStatus("Speedtest finalizó. Validando métricas, Result ID y URL (" +
                    speedtestResultExtractionAttempt + "/" +
                    MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS + ")...");''',
    "Speedtest extraction status",
)

main = replace_once(
    main,
    '''        setStatus("Speedtest terminó, pero no se pudieron validar sus métricas. Reintentando...");
        SpeedtestService.update(this,
            "No se pudieron leer métricas Speedtest - prueba " + currentRun,''',
    '''        setStatus("Speedtest terminó, pero faltan métricas, Result ID o URL. Reintentando...");
        SpeedtestService.update(this,
            "Speedtest incompleto: métricas/Result ID/URL - prueba " + currentRun,''',
    "Speedtest failure status",
)

main = replace_once(
    main,
    '''    private boolean hasStoredSpeedtestResult() {
        return SpeedtestResultExtractor.positive(download) &&
            SpeedtestResultExtractor.positive(upload) &&
            SpeedtestResultExtractor.positive(ping);
    }
''',
    '''    private boolean hasStoredSpeedtestResult() {
        return SpeedtestResultExtractor.positive(download) &&
            SpeedtestResultExtractor.positive(upload) &&
            SpeedtestResultExtractor.positive(ping) &&
            hasValidSpeedtestIdentity();
    }

    private boolean hasValidSpeedtestIdentity() {
        String id = resultId == null ? "" : resultId.trim();
        String url = resultUrl == null ? "" : resultUrl.trim();
        if (!id.matches("[A-Za-z0-9_-]{5,}")) return false;
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            String path = parsed.getPath();
            boolean validHost = host != null &&
                (host.equalsIgnoreCase("speedtest.net") ||
                 host.equalsIgnoreCase("www.speedtest.net") ||
                 host.toLowerCase(Locale.ROOT).endsWith(".speedtest.net"));
            return validHost && path != null &&
                path.matches(".*/result/" + Pattern.quote(id) + "/?");
        } catch (Exception ignored) {
            return false;
        }
    }
''',
    "mandatory Speedtest identity",
)

main = replace_once(
    main,
    '''        if (!hasStoredSpeedtestResult()) {
            setStatus("No se guardó: faltan métricas verificadas de Speedtest.");''',
    '''        if (!hasStoredSpeedtestResult()) {
            setStatus("No se guardó: faltan métricas, Result ID o URL verificados de Speedtest.");''',
    "save guard message",
)

main = replace_once(
    main,
    '''            "  Result ID    : " + (resultId.isEmpty()  ? "N/A" : resultId)  + "\\n" +
            "  URL          : " + (resultUrl.isEmpty() ? "N/A" : resultUrl) + "\\n\\n" +''',
    '''            "  Result ID    : " + resultId + "\\n" +
            "  URL          : " + resultUrl + "\\n\\n" +''',
    "TXT mandatory Speedtest identity",
)

# Defensive URL normalization: only preserve a Speedtest result URL that yielded an ID.
main = replace_once(
    main,
    '''    private void completeSpeedtestFromUrl(String url) {
        if (url != null) {
            Matcher matcher = Pattern.compile("result/([\\w-]+)").matcher(url);
            if (matcher.find()) resultId = matcher.group(1);
            resultUrl = url;
        }
        completeSpeedtest();
    }
''',
    '''    private void completeSpeedtestFromUrl(String url) {
        if (url != null) {
            Matcher matcher = Pattern.compile("/result/([\\w-]+)(?:/|$)").matcher(url);
            if (matcher.find()) {
                resultId = matcher.group(1);
                resultUrl = url;
            }
        }
        completeSpeedtest();
    }
''',
    "Speedtest URL identity extraction",
)

gradle = replace_once(gradle, "versionCode 7", "versionCode 8", "versionCode")
gradle = replace_once(
    gradle,
    'versionName "1.7-custom-tab-metrics"',
    'versionName "1.8-speedtest-result-id"',
    "versionName",
)

for marker in (
    "private boolean hasValidSpeedtestIdentity()",
    "path.matches(\".*/result/\" + Pattern.quote(id) + \"/?\")",
    'versionName "1.8-speedtest-result-id"',
):
    source = main if marker != 'versionName "1.8-speedtest-result-id"' else gradle
    if marker not in source:
        raise RuntimeError(f"missing required marker: {marker}")

main_path.write_text(main, encoding="utf-8")
gradle_path.write_text(gradle, encoding="utf-8")
