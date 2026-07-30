#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('.')
service_path = ROOT / 'app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java'
main_path = ROOT / 'app/src/main/java/com/netlife/speedtestnl/MainActivity.java'

service = service_path.read_text(encoding='utf-8')
main = main_path.read_text(encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected 1 match, found {count}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# nPerf: trust only labelled metrics; keep latency mandatory.
# ---------------------------------------------------------------------------
service = replace_once(
    service,
    '''        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) output.add(viewId);

''',
    '',
    'remove accessibility view IDs from metric text',
)

service, count = re.subn(
    r'''    private NperfBrowserCoordinator\.Result extractStrictResult\(
            List<String> lines, String joined\) \{.*?
    private NperfBrowserCoordinator\.Result withoutBaseline''',
    '''    private NperfBrowserCoordinator.Result extractStrictResult(
            List<String> lines, String joined) {
        return NperfResultParser.parse(lines, joined);
    }

    private NperfBrowserCoordinator.Result withoutBaseline''',
    service,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f'replace nPerf strict parser: expected 1 match, found {count}')

old_running = '''        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);
        if (hasValidThroughput(sanitized)) currentResult.merge(sanitized);

        long sinceConfirmed = now - startConfirmedAt;
        boolean throughput = hasValidThroughput(currentResult);

        if (!throughput) {
            if (sinceConfirmed >= START_DATA_TIMEOUT_MS) {
                fail("START_DATA_TIMEOUT",
                    "nPerf confirmó el inicio, pero no produjo descarga y subida");
                return;
            }
            status("RUNNING",
                "nPerf iniciado; esperando datos reales de descarga y subida...");
            return;
        }
'''
new_running = '''        NperfBrowserCoordinator.Result sanitized = withoutBaseline(observed);
        mergeValidatedMetrics(currentResult, sanitized);

        long sinceConfirmed = now - startConfirmedAt;
        boolean throughput = hasValidThroughput(currentResult);
        boolean latencyReady = NperfResultParser.positive(currentResult.latency);

        if (!throughput || !latencyReady) {
            if (sinceConfirmed >= START_DATA_TIMEOUT_MS) {
                fail("START_DATA_TIMEOUT",
                    "nPerf confirmó el inicio, pero no produjo descarga, subida y latencia válidas");
                return;
            }
            status("RUNNING", throughput
                ? "nPerf produjo descarga y subida; esperando latencia final..."
                : "nPerf iniciado; esperando datos reales de descarga y subida...");
            return;
        }
'''
service = replace_once(service, old_running, new_running,
    'require latency while nPerf is running')

service = replace_once(
    service,
    '''        status("RESULT_CANDIDATE", "nPerf: ↓ " + currentResult.download +
            " Mb/s · ↑ " + currentResult.upload +
            " Mb/s; verificando finalización...");''',
    '''        status("RESULT_CANDIDATE", "nPerf: ↓ " + currentResult.download +
            " Mb/s · ↑ " + currentResult.upload + " Mb/s · Latencia " +
            currentResult.latency + " ms; verificando finalización...");''',
    'include nPerf latency in verified status',
)

old_throughput = '''    private boolean hasValidThroughput(NperfBrowserCoordinator.Result result) {
        return result != null && isPositiveMetric(result.download) &&
            isPositiveMetric(result.upload);
    }

    private boolean isPositiveMetric(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" :
                value.replace(',', '.').trim());
            return parsed > 0.0d && parsed < 100000.0d;
        } catch (Exception ignored) {
            return false;
        }
    }
'''
new_throughput = '''    private void mergeValidatedMetrics(NperfBrowserCoordinator.Result target,
            NperfBrowserCoordinator.Result source) {
        if (target == null || source == null) return;
        if (NperfResultParser.positive(source.download)) target.download = source.download;
        if (NperfResultParser.positive(source.upload)) target.upload = source.upload;
        if (NperfResultParser.positive(source.latency)) target.latency = source.latency;
        if (NperfResultParser.positive(source.jitter)) target.jitter = source.jitter;
        if (!source.server.isEmpty()) target.server = source.server;
        if (!source.operator.isEmpty()) target.operator = source.operator;
        if (!source.resultId.isEmpty()) target.resultId = source.resultId;
        if (!source.resultUrl.isEmpty()) target.resultUrl = source.resultUrl;
    }

    private boolean hasValidThroughput(NperfBrowserCoordinator.Result result) {
        return result != null && NperfResultParser.positive(result.download) &&
            NperfResultParser.positive(result.upload);
    }

    private boolean hasCompleteNperfResult(NperfBrowserCoordinator.Result result) {
        return NperfResultParser.hasRequiredMetrics(result);
    }

    private boolean isPositiveMetric(String value) {
        return NperfResultParser.positive(value);
    }
'''
service = replace_once(service, old_throughput, new_throughput,
    'nPerf validated metric helpers')

service = replace_once(
    service,
    '''        if (terminalSent || startConfirmedAt == 0L ||
                !hasValidThroughput(currentResult)) {''',
    '''        if (terminalSent || startConfirmedAt == 0L ||
                !hasCompleteNperfResult(currentResult)) {''',
    'block incomplete nPerf completion',
)

# ---------------------------------------------------------------------------
# Speedtest: never transition by URL alone; extract download/upload/ping first.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '''    private ActivityResultLauncher<Intent> nperfBrowserLauncher;
    private boolean nperfBrowserActive = false;''',
    '''    private ActivityResultLauncher<Intent> nperfBrowserLauncher;
    private boolean nperfBrowserActive = false;
    private final AtomicBoolean speedtestResultExtractionStarted =
        new AtomicBoolean(false);
    private int speedtestResultExtractionAttempt = 0;
    private static final int MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS = 12;''',
    'Speedtest result extraction fields',
)

main = replace_once(
    main,
    '''        saved.set(false);
        errorDetected.set(false);

        nDownload = "";''',
    '''        saved.set(false);
        errorDetected.set(false);
        speedtestResultExtractionStarted.set(false);
        speedtestResultExtractionAttempt = 0;

        nDownload = "";''',
    'reset Speedtest result extraction',
)

main = replace_once(
    main,
    '''            saved.set(false);
            errorDetected.set(false);
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 3000);''',
    '''            saved.set(false);
            errorDetected.set(false);
            speedtestResultExtractionStarted.set(false);
            speedtestResultExtractionAttempt = 0;
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 3000);''',
    'reset extraction on Speedtest retry',
)

main, count = re.subn(
    r'''    private void extractMetricsThenStartNperf\(\) \{.*?
    // ══════════════════════════════════════════════════════════════════════
    // PROCESAR DATOS''',
    '''    private void extractMetricsThenStartNperf() {
        captureSpeedtestResultMetrics();
    }

    private void captureSpeedtestResultMetrics() {
        if (!"speedtest".equals(phase) ||
                !speedtestResultExtractionStarted.get()) return;
        if (webView == null) {
            failSpeedtestMetricExtraction();
            return;
        }

        speedtestResultExtractionAttempt++;
        webView.evaluateJavascript(SpeedtestResultExtractor.javascript(), value -> {
            SpeedtestResultExtractor.Metrics metrics =
                SpeedtestResultExtractor.parse(value);
            if (SpeedtestResultExtractor.positive(metrics.download)) {
                download = metrics.download;
            }
            if (SpeedtestResultExtractor.positive(metrics.upload)) {
                upload = metrics.upload;
            }
            if (SpeedtestResultExtractor.positive(metrics.ping)) {
                ping = metrics.ping;
            }
            if (SpeedtestResultExtractor.positive(metrics.jitter)) {
                jitter = metrics.jitter;
            }

            showPanel();
            if (hasStoredSpeedtestResult()) {
                setStatus("Speedtest verificado. Abriendo nPerf...");
                SpeedtestService.update(this,
                    "Speedtest verificado - prueba " + currentRun,
                    "Prueba " + currentRun + " de " + totalRuns);
                handler.postDelayed(this::transitionToNperf, 600L);
                return;
            }

            if (speedtestResultExtractionAttempt <
                    MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS) {
                setStatus("Speedtest finalizó. Leyendo descarga, subida y ping (" +
                    speedtestResultExtractionAttempt + "/" +
                    MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS + ")...");
                handler.postDelayed(this::captureSpeedtestResultMetrics, 1500L);
            } else {
                failSpeedtestMetricExtraction();
            }
        });
    }

    private void failSpeedtestMetricExtraction() {
        if (!speedtestResultExtractionStarted.compareAndSet(true, false)) return;
        saved.set(false);
        setStatus("Speedtest terminó, pero no se pudieron validar sus métricas. Reintentando...");
        SpeedtestService.update(this,
            "No se pudieron leer métricas Speedtest - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::retryRun, 1200L);
    }

    private boolean hasStoredSpeedtestResult() {
        return SpeedtestResultExtractor.positive(download) &&
            SpeedtestResultExtractor.positive(upload) &&
            SpeedtestResultExtractor.positive(ping);
    }

    private boolean hasStoredNperfResult() {
        return NperfResultParser.positive(nDownload) &&
            NperfResultParser.positive(nUpload) &&
            NperfResultParser.positive(nPing);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROCESAR DATOS''',
    main,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f'replace Speedtest final metric extraction: expected 1 match, found {count}')

main = replace_once(
    main,
    '''    private void saveTxt() {
        if (!"nperf".equals(phase) || !nSaved.get()) return;
        if (!finalSaveStarted.compareAndSet(false, true)) return;''',
    '''    private void saveTxt() {
        if (!"nperf".equals(phase) || !nSaved.get()) return;
        if (!hasStoredSpeedtestResult()) {
            setStatus("No se guardó: faltan métricas verificadas de Speedtest.");
            showErrorDialog();
            return;
        }
        if (!hasStoredNperfResult()) {
            nSaved.set(false);
            showNperfBrowserDecision("INCOMPLETE_RESULT",
                "nPerf no devolvió descarga, subida y latencia válidas");
            return;
        }
        if (!finalSaveStarted.compareAndSet(false, true)) return;''',
    'guard combined TXT metrics',
)

main = replace_once(
    main,
    '''            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                showNperfBrowserDecision(
                    "INCOMPLETE_RESULT",
                    "nPerf devolvió un resultado sin descarga o subida");
                return;
            }''',
    '''            if (!hasStoredNperfResult()) {
                showNperfBrowserDecision(
                    "INCOMPLETE_RESULT",
                    "nPerf devolvió un resultado sin descarga, subida o latencia válida");
                return;
            }''',
    'validate nPerf latency in MainActivity',
)

main, count = re.subn(
    r'''    private void completeSpeedtestFromUrl\(String url\) \{.*?
    private void transitionToNperf\(\) \{''',
    '''    private void completeSpeedtestFromUrl(String url) {
        if (url != null) {
            Matcher matcher = Pattern.compile("result/([\\\\w-]+)").matcher(url);
            if (matcher.find()) resultId = matcher.group(1);
            resultUrl = url;
        }
        completeSpeedtest();
    }

    private void completeSpeedtest() {
        if (!"speedtest".equals(phase) ||
                !speedtestResultExtractionStarted.compareAndSet(false, true)) {
            return;
        }
        saved.set(true);
        speedtestResultExtractionAttempt = 0;
        setStatus("Speedtest finalizado. Validando descarga, subida, ping y jitter...");
        handler.post(this::extractMetricsThenStartNperf);
    }

    private void transitionToNperf() {''',
    main,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f'replace Speedtest completion gate: expected 1 match, found {count}')

main = replace_once(
    main,
    '''        saved.set(false);
        errorDetected.set(false);
        setSpeedtestUserAgent();''',
    '''        saved.set(false);
        errorDetected.set(false);
        speedtestResultExtractionStarted.set(false);
        speedtestResultExtractionAttempt = 0;
        setSpeedtestUserAgent();''',
    'reset extraction on Speedtest reload',
)

# Sanity checks.
for marker in (
    'return NperfResultParser.parse(lines, joined);',
    '!hasCompleteNperfResult(currentResult)',
    'MAX_SPEEDTEST_RESULT_EXTRACTION_ATTEMPTS = 12',
    'SpeedtestResultExtractor.javascript()',
    'nPerf devolvió un resultado sin descarga, subida o latencia válida',
    'No se guardó: faltan métricas verificadas de Speedtest.',
):
    if marker not in service + main:
        raise RuntimeError(f'missing required marker: {marker}')

service_path.write_text(service, encoding='utf-8')
main_path.write_text(main, encoding='utf-8')
