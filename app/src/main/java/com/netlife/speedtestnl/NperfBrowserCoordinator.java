package com.netlife.speedtestnl;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Persistent coordination channel between MainActivity, the Custom Tab host,
 * the accessibility service and the optional Android share target.
 */
final class NperfBrowserCoordinator {

    static final String ACTION_STATUS =
        "com.netlife.speedtestnl.NPERF_BROWSER_STATUS";
    static final String ACTION_RESULT =
        "com.netlife.speedtestnl.NPERF_BROWSER_RESULT";
    static final String ACTION_ERROR =
        "com.netlife.speedtestnl.NPERF_BROWSER_ERROR";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_DETAIL = "detail";
    static final String EXTRA_DOWNLOAD = "download";
    static final String EXTRA_UPLOAD = "upload";
    static final String EXTRA_LATENCY = "latency";
    static final String EXTRA_JITTER = "jitter";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_OPERATOR = "operator";
    static final String EXTRA_RESULT_ID = "result_id";
    static final String EXTRA_RESULT_URL = "result_url";

    private static final String PREFS = "nperf_browser_session";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_BROWSER = "browser";
    private static final String KEY_STARTED = "started";
    private static final String KEY_RUN = "run";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_STATE = "state";
    private static final String KEY_RESULT_JSON = "result_json";

    private NperfBrowserCoordinator() { }

    static String begin(Context context, int run, int total) {
        String token = UUID.randomUUID().toString();
        prefs(context).edit()
            .clear()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TOKEN, token)
            .putLong(KEY_STARTED, SystemClock.elapsedRealtime())
            .putInt(KEY_RUN, run)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_STATE, "OPENING")
            .apply();
        sendStatus(context, token, "OPENING", "Abriendo nPerf en el navegador...");
        return token;
    }

    static boolean isActive(Context context) {
        return prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    static boolean isActiveToken(Context context, String token) {
        return isActive(context) && token != null &&
            token.equals(prefs(context).getString(KEY_TOKEN, ""));
    }

    static String getToken(Context context) {
        return prefs(context).getString(KEY_TOKEN, "");
    }

    static long getStartedElapsed(Context context) {
        return prefs(context).getLong(KEY_STARTED, 0L);
    }

    static int getRun(Context context) {
        return prefs(context).getInt(KEY_RUN, 0);
    }

    static int getTotal(Context context) {
        return prefs(context).getInt(KEY_TOTAL, 0);
    }

    static void setBrowserPackage(Context context, String packageName) {
        prefs(context).edit().putString(KEY_BROWSER,
            packageName == null ? "" : packageName).apply();
    }

    static String getBrowserPackage(Context context) {
        return prefs(context).getString(KEY_BROWSER, "");
    }

    static void sendStatus(Context context, String token,
            String state, String detail) {
        if (!isActiveToken(context, token)) return;
        prefs(context).edit().putString(KEY_STATE, safe(state)).apply();
        Intent intent = new Intent(ACTION_STATUS)
            .setPackage(context.getPackageName())
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_STATE, safe(state))
            .putExtra(EXTRA_DETAIL, safe(detail));
        context.sendBroadcast(intent);
    }

    static void complete(Context context, String token, Result result) {
        if (!isActiveToken(context, token) || result == null) return;
        prefs(context).edit()
            .putString(KEY_RESULT_JSON, result.toJson().toString())
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_STATE, "COMPLETE")
            .apply();

        Intent intent = result.toIntent(ACTION_RESULT, context.getPackageName())
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_STATE, "COMPLETE");
        context.sendBroadcast(intent);
    }

    static void fail(Context context, String token, String code, String detail) {
        if (!isActiveToken(context, token)) return;
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_STATE, "ERROR")
            .apply();
        Intent intent = new Intent(ACTION_ERROR)
            .setPackage(context.getPackageName())
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_STATE, safe(code))
            .putExtra(EXTRA_DETAIL, safe(detail));
        context.sendBroadcast(intent);
    }

    static void cancel(Context context, String token) {
        if (token != null && !isActiveToken(context, token)) return;
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply();
    }

    static Result parseSharedText(String text) {
        String source = text == null ? "" : text;
        Result result = new Result();
        result.resultUrl = firstUrl(source);
        result.resultId = firstMatch(source,
            "(?i)(?:result(?:ado)?\\s*(?:id)?|id)\\s*[:#-]?\\s*([A-Za-z0-9_-]{5,})");
        result.download = metric(source,
            "(?:download|descarga|bajada|downlink)", "(?:mbps|mb/s|mbit/s)");
        result.upload = metric(source,
            "(?:upload|subida|carga|uplink)", "(?:mbps|mb/s|mbit/s)");
        result.latency = metric(source,
            "(?:latency|latencia|ping)", "(?:ms)");
        result.jitter = metric(source, "(?:jitter)", "(?:ms)");
        result.server = firstMatch(source,
            "(?i)(?:server|servidor)\\s*[:=-]\\s*([^\\n\\r|]{2,80})");
        result.operator = firstMatch(source,
            "(?i)(?:operator|operador|isp|provider|proveedor)\\s*[:=-]\\s*([^\\n\\r|]{2,80})");
        return result;
    }

    private static String metric(String source, String label, String unit) {
        String direct = firstMatch(source,
            "(?i)" + label + "[^0-9]{0,25}([0-9]+(?:[.,][0-9]+)?)\\s*" + unit);
        if (!direct.isEmpty()) return direct.replace(',', '.');
        String reversed = firstMatch(source,
            "(?i)([0-9]+(?:[.,][0-9]+)?)\\s*" + unit + "[^\\n\\r]{0,25}" + label);
        return reversed.replace(',', '.');
    }

    private static String firstUrl(String source) {
        return firstMatch(source,
            "(?i)(https?://(?:www\\.)?nperf\\.(?:com|net)/[^\\s<>\"]+)");
    }

    private static String firstMatch(String source, String regex) {
        try {
            java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(regex).matcher(source == null ? "" : source);
            return matcher.find() ? safe(matcher.group(1)) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Result {
        String download = "";
        String upload = "";
        String latency = "";
        String jitter = "";
        String server = "";
        String operator = "";
        String resultId = "";
        String resultUrl = "";

        boolean hasThroughput() {
            return !download.isEmpty() && !upload.isEmpty();
        }

        void merge(Result other) {
            if (other == null) return;
            download = prefer(other.download, download);
            upload = prefer(other.upload, upload);
            latency = prefer(other.latency, latency);
            jitter = prefer(other.jitter, jitter);
            server = prefer(other.server, server);
            operator = prefer(other.operator, operator);
            resultId = prefer(other.resultId, resultId);
            resultUrl = prefer(other.resultUrl, resultUrl);
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put(EXTRA_DOWNLOAD, download);
                object.put(EXTRA_UPLOAD, upload);
                object.put(EXTRA_LATENCY, latency);
                object.put(EXTRA_JITTER, jitter);
                object.put(EXTRA_SERVER, server);
                object.put(EXTRA_OPERATOR, operator);
                object.put(EXTRA_RESULT_ID, resultId);
                object.put(EXTRA_RESULT_URL, resultUrl);
            } catch (Exception ignored) { }
            return object;
        }

        Intent toIntent(String action, String packageName) {
            return new Intent(action)
                .setPackage(packageName)
                .putExtra(EXTRA_DOWNLOAD, download)
                .putExtra(EXTRA_UPLOAD, upload)
                .putExtra(EXTRA_LATENCY, latency)
                .putExtra(EXTRA_JITTER, jitter)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_OPERATOR, operator)
                .putExtra(EXTRA_RESULT_ID, resultId)
                .putExtra(EXTRA_RESULT_URL, resultUrl);
        }

        private static String prefer(String candidate, String current) {
            return candidate == null || candidate.trim().isEmpty()
                ? safe(current) : candidate.trim();
        }
    }
}
