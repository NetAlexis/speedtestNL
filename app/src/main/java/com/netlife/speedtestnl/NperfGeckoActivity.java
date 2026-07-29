package com.netlife.speedtestnl;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.util.Locale;

/**
 * Runs the public nPerf web test in a self-contained Firefox/Gecko engine.
 *
 * A bundled WebExtension inspects only nperf.com, reports state and metrics,
 * and requests native Android taps for controls that require a real pointer
 * event. No external browser or nPerf application is required.
 */
public class NperfGeckoActivity extends AppCompatActivity {

    public static final String EXTRA_RUN = "run";
    public static final String EXTRA_TOTAL_RUNS = "total_runs";
    public static final String EXTRA_LOCATION_MODE = "location_mode";

    public static final String EXTRA_DOWNLOAD = "nperf_download";
    public static final String EXTRA_UPLOAD = "nperf_upload";
    public static final String EXTRA_LATENCY = "nperf_latency";
    public static final String EXTRA_JITTER = "nperf_jitter";
    public static final String EXTRA_SERVER = "nperf_server";
    public static final String EXTRA_OPERATOR = "nperf_operator";
    public static final String EXTRA_RESULT_ID = "nperf_result_id";
    public static final String EXTRA_RESULT_URL = "nperf_result_url";
    public static final String EXTRA_ERROR_CODE = "nperf_error_code";
    public static final String EXTRA_ERROR_DETAIL = "nperf_error_detail";

    private static final String TAG = "SpeedtestNL-Gecko";
    private static final String NPERF_URL = "https://www.nperf.com/es/index";
    private static final String EXTENSION_URI =
        "resource://android/assets/nperf_automation/";
    private static final String EXTENSION_ID =
        "nperf-automation@speedtestnl.local";
    private static final String NATIVE_APP = "speedtestnl";
    private static final int LOCATION_REQUEST = 730;
    private static final long TEST_TIMEOUT_MS = 7 * 60 * 1000L;

    private static GeckoRuntime runtime;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private GeckoView geckoView;
    private GeckoSession session;
    private TextView statusView;
    private TextView downloadView;
    private TextView uploadView;
    private TextView latencyView;
    private TextView jitterView;
    private ProgressBar progressBar;

    private GeckoSession.PermissionDelegate.Callback pendingAndroidPermission;
    private String locationMode = "auto";
    private boolean finished = false;
    private int runNumber = 1;
    private int totalRuns = 1;

    private String download = "";
    private String upload = "";
    private String latency = "";
    private String jitter = "";
    private String server = "";
    private String operator = "";
    private String resultId = "";
    private String resultUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_nperf_gecko);

        geckoView = findViewById(R.id.geckoNperfView);
        statusView = findViewById(R.id.tvNperfGeckoStatus);
        downloadView = findViewById(R.id.tvNperfGeckoDownload);
        uploadView = findViewById(R.id.tvNperfGeckoUpload);
        latencyView = findViewById(R.id.tvNperfGeckoLatency);
        jitterView = findViewById(R.id.tvNperfGeckoJitter);
        progressBar = findViewById(R.id.progressNperfGecko);

        Intent source = getIntent();
        runNumber = Math.max(1, source.getIntExtra(EXTRA_RUN, 1));
        totalRuns = Math.max(1, source.getIntExtra(EXTRA_TOTAL_RUNS, 1));
        locationMode = source.getStringExtra(EXTRA_LOCATION_MODE);
        if (locationMode == null || locationMode.trim().isEmpty()) {
            locationMode = "auto";
        }

        setStatus("Preparando GeckoView para nPerf " + runNumber + "/" + totalRuns + "...");
        initializeGecko();
        handler.postDelayed(() -> fail(
            "TIMEOUT",
            "nPerf no terminó dentro del tiempo máximo de 7 minutos"
        ), TEST_TIMEOUT_MS);
    }

    private void initializeGecko() {
        try {
            if (runtime == null) {
                synchronized (NperfGeckoActivity.class) {
                    if (runtime == null) {
                        GeckoRuntimeSettings runtimeSettings =
                            new GeckoRuntimeSettings.Builder()
                                .javaScriptEnabled(true)
                                .consoleOutput(true)
                                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                                .build();
                        runtime = GeckoRuntime.create(
                            getApplicationContext(), runtimeSettings);
                    }
                }
            }

            GeckoSessionSettings sessionSettings =
                new GeckoSessionSettings.Builder()
                    .allowJavascript(true)
                    .usePrivateMode(false)
                    .useTrackingProtection(false)
                    .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                    .build();

            session = new GeckoSession(sessionSettings);
            configureSessionDelegates();
            session.open(runtime);
            geckoView.setSession(session);

            setStatus("Instalando automatización nPerf...");
            runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                .accept(
                    extension -> {
                        if (finished) return;
                        session.getWebExtensionController().setMessageDelegate(
                            extension, messageDelegate, NATIVE_APP);
                        requestLocationPermissionProactively();
                        setStatus("Cargando nPerf en GeckoView...");
                        session.loadUri(NPERF_URL);
                    },
                    error -> fail(
                        "EXTENSION_INSTALL",
                        "No se pudo instalar la automatización nPerf: " +
                            safeMessage(error)
                    )
                );
        } catch (Throwable error) {
            fail("GECKO_INITIALIZATION",
                "No se pudo iniciar GeckoView: " + safeMessage(error));
        }
    }

    private void configureSessionDelegates() {
        session.setContentDelegate(new GeckoSession.ContentDelegate() { });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession geckoSession,
                    @NonNull String url) {
                if (isAllowedNperfUrl(url)) {
                    setStatus("nPerf cargando recursos...");
                    progressBar.setIndeterminate(true);
                }
            }

            @Override
            public void onPageStop(@NonNull GeckoSession geckoSession,
                    boolean success) {
                if (!success) {
                    fail("PAGE_LOAD", "GeckoView no pudo cargar la página de nPerf");
                } else {
                    setStatus("nPerf cargado. Esperando inicialización...");
                }
            }

            @Override
            public void onProgressChange(@NonNull GeckoSession geckoSession,
                    int progress) {
                progressBar.setIndeterminate(progress <= 0 || progress >= 100);
                if (progress > 0 && progress < 100) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progress);
                }
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(@NonNull GeckoSession geckoSession,
                    String url,
                    @NonNull java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    @NonNull Boolean hasUserGesture) {
                if (url == null) return;
                Log.i(TAG, "Location: " + url);
                if (!isAllowedNperfUrl(url) && !url.startsWith("about:")) {
                    fail("UNEXPECTED_NAVIGATION",
                        "nPerf intentó abrir un dominio no autorizado");
                }
            }

            @Override
            public GeckoResult<String> onLoadError(
                    @NonNull GeckoSession geckoSession,
                    String uri,
                    @NonNull org.mozilla.geckoview.WebRequestError error) {
                fail("NETWORK_LOAD",
                    "Error cargando nPerf: categoría " + error.category +
                        ", código " + error.code);
                return null;
            }
        });

        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(
                    @NonNull GeckoSession geckoSession,
                    String[] permissions,
                    @NonNull Callback callback) {
                handleAndroidPermissionRequest(permissions, callback);
            }

            @Override
            public GeckoResult<Integer> onContentPermissionRequest(
                    @NonNull GeckoSession geckoSession,
                    @NonNull ContentPermission permission) {
                if (!isAllowedNperfUrl(permission.uri)) {
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                }

                if (permission.permission == PERMISSION_GEOLOCATION) {
                    if ("omitir".equalsIgnoreCase(locationMode)) {
                        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                    }
                    setStatus("Ubicación solicitada por nPerf: aceptada automáticamente");
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }

                if (permission.permission == PERMISSION_PERSISTENT_STORAGE ||
                        permission.permission == PERMISSION_STORAGE_ACCESS ||
                        permission.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }

                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });
    }

    private final WebExtension.MessageDelegate messageDelegate =
        new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(@NonNull String nativeApp,
                    @NonNull Object message,
                    @NonNull WebExtension.MessageSender sender) {
                if (!NATIVE_APP.equals(nativeApp) || !(message instanceof JSONObject)) {
                    return GeckoResult.fromValue(null);
                }

                JSONObject data = (JSONObject) message;
                String href = data.optString("href", "");
                if (!isAllowedNperfUrl(href)) {
                    Log.w(TAG, "Rejected extension message from " + href);
                    return GeckoResult.fromValue(null);
                }

                handler.post(() -> processExtensionMessage(data));
                return GeckoResult.fromValue(null);
            }
        };

    private void processExtensionMessage(JSONObject data) {
        if (finished) return;
        String type = data.optString("type", "");
        Log.i(TAG, "Extension message: " + data);

        switch (type) {
            case "extension_ready":
                setStatus("Automatización nPerf activa");
                break;

            case "state":
                String detail = data.optString("detail", "nPerf procesando...");
                setStatus(detail);
                break;

            case "tap":
                String role = data.optString("role", "control");
                float x = (float) data.optDouble("x", -1d);
                float y = (float) data.optDouble("y", -1d);
                float viewportWidth = (float) data.optDouble("viewportWidth", -1d);
                float viewportHeight = (float) data.optDouble("viewportHeight", -1d);
                setStatus("Enviando toque Android a nPerf (" + role + ")...");
                dispatchNativeTap(x, y, viewportWidth, viewportHeight);
                break;

            case "metrics":
                updateMetrics(data);
                break;

            case "complete":
                updateMetrics(data);
                completeWithResult();
                break;

            case "error":
                fail(data.optString("code", "NPERF_ERROR"),
                    data.optString("detail", "nPerf informó un error"));
                break;

            default:
                break;
        }
    }

    private void updateMetrics(JSONObject data) {
        download = prefer(data.optString("download", ""), download);
        upload = prefer(data.optString("upload", ""), upload);
        latency = prefer(data.optString("latency", ""), latency);
        jitter = prefer(data.optString("jitter", ""), jitter);
        server = prefer(data.optString("server", ""), server);
        operator = prefer(data.optString("operator", ""), operator);
        resultId = prefer(data.optString("resultId", ""), resultId);
        String candidateUrl = data.optString("resultUrl", "");
        if (isAllowedNperfUrl(candidateUrl)) resultUrl = candidateUrl;

        downloadView.setText("↓ " + metric(download, "Mb/s"));
        uploadView.setText("↑ " + metric(upload, "Mb/s"));
        latencyView.setText("Ping " + metric(latency, "ms"));
        jitterView.setText("Jitter " + metric(jitter, "ms"));
    }

    private void dispatchNativeTap(float cssX, float cssY,
            float viewportWidth, float viewportHeight) {
        if (cssX < 0 || cssY < 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            fail("INVALID_TAP", "nPerf devolvió coordenadas de control inválidas");
            return;
        }

        geckoView.post(() -> {
            if (finished || geckoView.getWidth() <= 0 || geckoView.getHeight() <= 0) return;
            float x = cssX * geckoView.getWidth() / viewportWidth;
            float y = cssY * geckoView.getHeight() / viewportHeight;
            x = Math.max(1f, Math.min(geckoView.getWidth() - 1f, x));
            y = Math.max(1f, Math.min(geckoView.getHeight() - 1f, y));

            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            geckoView.dispatchTouchEvent(down);
            down.recycle();

            final float finalX = x;
            final float finalY = y;
            handler.postDelayed(() -> {
                if (finished) return;
                MotionEvent up = MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    finalX,
                    finalY,
                    0
                );
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                geckoView.dispatchTouchEvent(up);
                up.recycle();
            }, 110L);
        });
    }

    private void requestLocationPermissionProactively() {
        if ("omitir".equalsIgnoreCase(locationMode)) return;
        if (hasLocationPermission()) return;
        setStatus("Android solicitará ubicación una sola vez para nPerf");
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_REQUEST);
    }

    private void handleAndroidPermissionRequest(String[] permissions,
            GeckoSession.PermissionDelegate.Callback callback) {
        if (permissions == null || permissions.length == 0) {
            callback.reject();
            return;
        }

        boolean onlyLocation = true;
        for (String permission : permissions) {
            if (!Manifest.permission.ACCESS_FINE_LOCATION.equals(permission) &&
                    !Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
                onlyLocation = false;
                break;
            }
        }

        if (!onlyLocation || "omitir".equalsIgnoreCase(locationMode)) {
            callback.reject();
            return;
        }

        if (hasLocationPermission()) {
            callback.grant();
            return;
        }

        pendingAndroidPermission = callback;
        ActivityCompat.requestPermissions(this, permissions, LOCATION_REQUEST);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) return;

        boolean granted = hasLocationPermission();
        if (pendingAndroidPermission != null) {
            if (granted) pendingAndroidPermission.grant();
            else pendingAndroidPermission.reject();
            pendingAndroidPermission = null;
        }

        setStatus(granted
            ? "Ubicación Android concedida; nPerf autorizado automáticamente"
            : "Ubicación Android denegada; nPerf continuará sin ubicación precisa");
    }

    private void completeWithResult() {
        if (finished) return;
        if (download.isEmpty() || upload.isEmpty()) {
            fail("INCOMPLETE_RESULT",
                "nPerf finalizó sin valores válidos de descarga y subida");
            return;
        }

        finished = true;
        handler.removeCallbacksAndMessages(null);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(100);
        setStatus("nPerf completado. Regresando a Speedtest NL...");

        Intent result = new Intent();
        result.putExtra(EXTRA_DOWNLOAD, download);
        result.putExtra(EXTRA_UPLOAD, upload);
        result.putExtra(EXTRA_LATENCY, latency);
        result.putExtra(EXTRA_JITTER, jitter);
        result.putExtra(EXTRA_SERVER, server);
        result.putExtra(EXTRA_OPERATOR, operator);
        result.putExtra(EXTRA_RESULT_ID, resultId);
        result.putExtra(EXTRA_RESULT_URL, resultUrl);
        setResult(Activity.RESULT_OK, result);
        handler.postDelayed(this::finish, 500L);
    }

    private void fail(String code, String detail) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        Log.e(TAG, code + ": " + detail);
        setStatus("Error nPerf: " + detail);
        progressBar.setIndeterminate(false);

        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR_CODE, code == null ? "NPERF_ERROR" : code);
        result.putExtra(EXTRA_ERROR_DETAIL,
            detail == null ? "Error no especificado" : detail);
        setResult(Activity.RESULT_CANCELED, result);
        handler.postDelayed(this::finish, 900L);
    }

    private boolean isAllowedNperfUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return false;
            boolean secure = "https".equalsIgnoreCase(scheme) ||
                "http".equalsIgnoreCase(scheme);
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return secure && (
                lowerHost.equals("nperf.com") ||
                lowerHost.endsWith(".nperf.com") ||
                lowerHost.equals("nperf.net") ||
                lowerHost.endsWith(".nperf.net")
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private String prefer(String candidate, String current) {
        return candidate == null || candidate.trim().isEmpty()
            ? current : candidate.trim();
    }

    private String metric(String value, String unit) {
        return value == null || value.isEmpty() ? "-" : value + " " + unit;
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "error desconocido";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message.trim();
    }

    private void setStatus(String message) {
        if (statusView != null) statusView.setText(message);
        Log.i(TAG, message);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        fail("USER_CANCELLED", "La prueba nPerf fue cancelada por el usuario");
    }
}
