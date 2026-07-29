package com.netlife.speedtestnl.nperf;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Stable application-side contract for the proprietary nPerf Engine SDK.
 *
 * The vendor-specific adapter must implement this interface. MainActivity only
 * depends on this contract, so future nPerf SDK updates remain isolated from the
 * Speedtest flow and result persistence.
 */
public interface NperfEngine {

    enum State {
        PREPARING,
        SELECTING_SERVER,
        LATENCY,
        DOWNLOAD,
        UPLOAD,
        FINALIZING
    }

    enum Metric {
        DOWNLOAD_MBPS,
        UPLOAD_MBPS,
        LATENCY_MS,
        JITTER_MS
    }

    final class Request {
        public final int runNumber;
        public final int totalRuns;
        public final int timeoutSeconds;
        public final boolean wifiOnly;
        public final String testTag;

        public Request(int runNumber, int totalRuns, int timeoutSeconds,
                boolean wifiOnly, String testTag) {
            this.runNumber = runNumber;
            this.totalRuns = totalRuns;
            this.timeoutSeconds = timeoutSeconds;
            this.wifiOnly = wifiOnly;
            this.testTag = testTag == null ? "" : testTag;
        }
    }

    final class Result {
        public final double downloadMbps;
        public final double uploadMbps;
        public final double latencyMs;
        public final double jitterMs;
        public final String server;
        public final String operator;
        public final String resultId;
        public final String resultUrl;
        public final Map<String, String> rawValues;

        public Result(double downloadMbps, double uploadMbps, double latencyMs,
                double jitterMs, String server, String operator, String resultId,
                String resultUrl, Map<String, String> rawValues) {
            this.downloadMbps = downloadMbps;
            this.uploadMbps = uploadMbps;
            this.latencyMs = latencyMs;
            this.jitterMs = jitterMs;
            this.server = safe(server);
            this.operator = safe(operator);
            this.resultId = safe(resultId);
            this.resultUrl = safe(resultUrl);
            this.rawValues = rawValues == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(rawValues));
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }

    interface Listener {
        void onState(State state, String message);
        void onMetric(Metric metric, double value);
        void onComplete(Result result);
        void onError(String code, String message, Throwable cause);
    }

    boolean isAvailable();

    String getUnavailableReason();

    void start(Request request, Listener listener);

    void cancel();

    void release();
}
