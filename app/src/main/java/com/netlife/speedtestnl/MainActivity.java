package com.netlife.speedtestnl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private TextView tvStatus, tvResultId, tvDownload, tvUpload;
    private TextView tvPing, tvJitter, tvCounter;
    private ProgressBar progressBar;
    private LinearLayout layoutResults, layoutJitter;

    private String resultId = "";
    private String resultUrl = "";
    private String download = "";
    private String upload = "";
    private String ping = "";
    private String jitter = "";
    private boolean pageLoaded = false;
    private boolean goPressed = false;
    private int pollCount = 0;
    private final AtomicBoolean saved = new AtomicBoolean(false);
    private final AtomicBoolean errorDetected = new AtomicBoolean(false);

    private String nDownload = "";
    private String nUpload = "";
    private String nPing = "";
    private String nServer = "";
    private String nOperator = "";
    private boolean nGoPressed = false;
    private boolean nPageLoaded = false;
    private int nPollCount = 0;
    private int nRetryCount = 0;
    private final AtomicBoolean nSaved = new AtomicBoolean(false);
    private final AtomicBoolean nErrorDetected = new AtomicBoolean(false);

    private String phase = "speedtest";
    private boolean watcherRunning = false;

    private int totalRuns = 3;
    private int maxRetries = 2;
    private int waitBetween = 5;
    private int currentRun = 0;
    private int currentRetry = 0;

    private boolean isRunning = false;
    private boolean isInBackground = false;
    private boolean geoPermShown = false;
    private String ubicacionMode = "preguntar";

    private static final String DRIVE_SCRIPT_URL =
        "https://script.google.com/macros/s/" +
        "AKfycbyCceC3xbYl11Cti_-vZCAY7e-CtVJy5q9s1INslmyyBI8z" +
        "KTg1BFQByrbH6zdCDKidbQ/exec";

    private static String buildSheetsUrl() {
        return "https://docs.google.com/spreadsheets/d/e/" +
            "2PACX-1vTvy40rzoGIjR-tZ6jCfPmdRoUi7tO_s_J44LqUV66iR" +
            "3_FMCU2d1u8TsWXL4djP5wyv-JgimW_gUkv" +
            "/pub?gid=0&single=true&output=csv";
    }

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int PERM_REQ = 100;
    private static final int PERM_REQ_NOTIF = 101;
    private static final int MAX_POLL = 120;
    private static final int MAX_NPERF_POLL = 80;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvResultId = findViewById(R.id.tvResultId);
        tvDownload = findViewById(R.id.tvDownload);
        tvUpload = findViewById(R.id.tvUpload);
        tvPing = findViewById(R.id.tvPing);
        tvJitter = findViewById(R.id.tvJitter);
        tvCounter = findViewById(R.id.tvCounter);
        progressBar = findViewById(R.id.progressBar);
        layoutResults = findViewById(R.id.layoutResults);
        layoutJitter = findViewById(R.id.layoutJitter);
        webView = findViewById(R.id.webView);

        acquireWakeLock();
        requestPerms();
        setupWebView();
        startForegroundService();
        startBannerWatcher();

        setStatus("Cargando configuracion...");
        loadConfigFromSheets();
    }

    @Override
    public void onBackPressed() {
        if (!isRunning) {
            super.onBackPressed();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Prueba en curso")
            .setMessage("La prueba continuará en segundo plano. ¿Desea minimizar la aplicación?")
            .setPositiveButton("Minimizar", (d, w) -> moveTaskToBack(true))
            .setNegativeButton("Cancelar prueba", (d, w) ->
                new AlertDialog.Builder(this)
                    .setTitle("Confirmar cancelacion")
                    .setMessage("¿Seguro que desea cancelar todas las pruebas?")
                    .setPositiveButton("Si, cancelar", (d2, w2) -> stopEverything("Pruebas canceladas"))
                    .setNegativeButton("No, continuar", null)
                    .setCancelable(false)
                    .show())
            .setCancelable(false)
            .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInBackground = true;
        if (webView != null && !isRunning) {
            webView.onPause();
            webView.pauseTimers();
        } else if (webView != null) {
            webView.resumeTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInBackground = false;
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
    }

    private void loadConfigFromSheets() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(buildSheetsUrl()).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",", 2);
                    if (parts.length < 2) continue;
                    String key = parts[0].trim().toLowerCase(Locale.ROOT).replace("\"", "");
                    String raw = parts[1].trim().replace("\"", "");

                    if (key.equals("ubicacion")) {
                        String mode = raw.replace("\r", "").replace("\n", "")
                            .toLowerCase(Locale.ROOT).trim();
                        if (mode.equals("auto") || mode.equals("omitir") || mode.equals("preguntar")) {
                            ubicacionMode = mode;
                        }
                        continue;
                    }

                    String numeric = raw.replaceAll("[^0-9]", "");
                    if (numeric.isEmpty()) continue;
                    int value = Integer.parseInt(numeric);
                    switch (key) {
                        case "repeticiones":
                            totalRuns = Math.max(1, Math.min(50, value));
                            break;
                        case "reintentos_si_falla":
                            maxRetries = Math.max(0, Math.min(5, value));
                            break;
                        case "espera_entre_pruebas_seg":
                            waitBetween = Math.max(0, Math.min(300, value));
                            break;
                        default:
                            break;
                    }
                }
                br.close();
                handler.post(() -> {
                    setStatus("Config: " + totalRuns + " pruebas");
                    handler.postDelayed(this::startRun, 1000);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    setStatus("Config por defecto: " + totalRuns + " pruebas");
                    handler.postDelayed(this::startRun, 1000);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void startForegroundService() {
        Intent i = new Intent(this, SpeedtestService.class);
        i.putExtra(SpeedtestService.EXTRA_STATUS, "Iniciando...");
        i.putExtra(SpeedtestService.EXTRA_PROGRESS, "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedtestNL::WakeLock");
            wakeLock.acquire(2 * 60 * 60 * 1000L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRun() {
        if (!isWifiConnected()) {
            showNoWifiDialog();
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog();
            return;
        }

        isRunning = true;
        currentRun++;
        currentRetry = 0;
        nRetryCount = 0;
        phase = "speedtest";
        resetState();
        layoutJitter.setVisibility(View.VISIBLE);

        String progress = "Prueba " + currentRun + " de " + totalRuns;
        tvCounter.setText(progress);
        setStatus("Iniciando " + currentRun + "/" + totalRuns + "...");
        SpeedtestService.update(this, "Iniciando prueba " + currentRun, progress);
        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);

        prepareWebView(false);
        webView.loadUrl("https://www.speedtest.net/en");
    }

    private void resetState() {
        resultId = "";
        resultUrl = "";
        download = "";
        upload = "";
        ping = "";
        jitter = "";
        pageLoaded = false;
        goPressed = false;
        pollCount = 0;
        saved.set(false);
        errorDetected.set(false);

        nDownload = "";
        nUpload = "";
        nPing = "";
        nServer = "";
        nOperator = "";
        nGoPressed = false;
        nPageLoaded = false;
        nPollCount = 0;
        nSaved.set(false);
        nErrorDetected.set(false);
    }

    private void prepareWebView(boolean nperf) {
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(nperf);
        cm.removeAllCookies(null);
        cm.flush();
        webView.getSettings().setUserAgentString(nperf
            ? "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    private void startBannerWatcher() {
        if (watcherRunning) return;
        watcherRunning = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning && currentRun > 0) {
                    watcherRunning = false;
                    return;
                }
                if (webView != null) {
                    webView.evaluateJavascript(
                        "(function(){" +
                        "var sels=['#didomi-notice-agree-button','#onetrust-accept-btn-handler'," +
                        "'[class*=cookie-accept]','[class*=accept-cookie]','button[aria-label*=close]'," +
                        "'button[aria-label*=Close]','[class*=modal-close]','[class*=dialog-close]'];" +
                        "for(var i=0;i<sels.length;i++){var e=document.querySelector(sels[i]);" +
                        "if(e&&e.offsetParent!==null){e.click();return true;}}" +
                        "var words=['ACEPTAR','ACCEPT','AGREE','CONTINUAR','CERRAR','CLOSE','OK'];" +
                        "var all=document.querySelectorAll('button,a[role=button]');" +
                        "for(var j=0;j<all.length;j++){var t=(all[j].textContent||'').trim().toUpperCase();" +
                        "if(words.indexOf(t)>-1&&all[j].offsetParent!==null){all[j].click();return true;}}" +
                        "return false;})()", null);
                }
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void stopBannerWatcher() {
        watcherRunning = false;
    }

    private void showNoWifiDialog() {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("Conexion WiFi requerida")
            .setMessage("Conéctese a una red WiFi y presione Aceptar.")
            .setPositiveButton("Aceptar", (d, w) -> {
                if (isWifiConnected()) resumeCurrentPhase();
                else handler.postDelayed(this::showNoWifiDialog, 500);
            })
            .setCancelable(false)
            .show());
    }

    private void showNoInternetDialog() {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("Sin Datos")
            .setMessage("No hay conexión a internet. Verifique la conexión y presione Aceptar.")
            .setPositiveButton("Aceptar", (d, w) -> {
                if (isConnected()) resumeCurrentPhase();
                else handler.postDelayed(this::showNoInternetDialog, 500);
            })
            .setCancelable(false)
            .show());
    }

    private void resumeCurrentPhase() {
        setStatus("Conexion restaurada. Reintentando...");
        if (phase.equals("nperf")) retryNperfOrShow("Conexión restaurada");
        else retryRun();
    }

    private void retryRun() {
        if (currentRetry < maxRetries) {
            currentRetry++;
            setStatus("Reintentando prueba " + currentRun + " (" + currentRetry + "/" + maxRetries + ")...");
            resultId = "";
            resultUrl = "";
            download = "";
            upload = "";
            ping = "";
            jitter = "";
            pageLoaded = false;
            goPressed = false;
            pollCount = 0;
            saved.set(false);
            errorDetected.set(false);
            handler.postDelayed(() -> {
                prepareWebView(false);
                webView.loadUrl("https://www.speedtest.net/en");
                progressBar.setVisibility(View.VISIBLE);
                layoutResults.setVisibility(View.GONE);
            }, 3000);
        } else {
            showSpeedtestErrorDialog();
        }
    }

    private void showSpeedtestErrorDialog() {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("Error en Speedtest")
            .setMessage("La prueba " + currentRun + " no pudo completarse.")
            .setPositiveButton("Intentar de nuevo", (d, w) -> {
                currentRetry = 0;
                saved.set(false);
                errorDetected.set(false);
                retryRun();
            })
            .setNegativeButton("Restablecer todo", (d, w) -> restartAllTests())
            .setCancelable(false)
            .show());
    }

    private void retryNperfOrShow(String reason) {
        if (nSaved.get()) return;
        if (nRetryCount < maxRetries) {
            nRetryCount++;
            nErrorDetected.set(false);
            setStatus("Reintentando nperf (" + nRetryCount + "/" + maxRetries + ")...");
            handler.postDelayed(this::startNperf, 3000);
        } else {
            showNperfErrorDialog(reason);
        }
    }

    private void showNperfErrorDialog(String reason) {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("nPerf no completado")
            .setMessage(reason + "\n\nPuede repetir nPerf o restablecer todas las pruebas desde el inicio.")
            .setPositiveButton("Intentar de nuevo", (d, w) -> {
                nRetryCount = 0;
                nSaved.set(false);
                nErrorDetected.set(false);
                startNperf();
            })
            .setNegativeButton("Restablecer todo", (d, w) -> restartAllTests())
            .setCancelable(false)
            .show());
    }

    private void restartAllTests() {
        handler.removeCallbacksAndMessages(null);
        stopBannerWatcher();
        currentRun = 0;
        currentRetry = 0;
        nRetryCount = 0;
        phase = "speedtest";
        isRunning = true;
        resetState();
        tvCounter.setText("");
        layoutResults.setVisibility(View.GONE);
        layoutJitter.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        acquireWakeLock();
        startForegroundService();
        startBannerWatcher();
        setStatus("Restableciendo todas las pruebas...");
        handler.postDelayed(this::startRun, 1500);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDatabaseEnabled(false);
        settings.setSaveFormData(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setGeolocationEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(30);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                if (ubicacionMode.equals("auto")) {
                    callback.invoke(origin, true, false);
                } else if (ubicacionMode.equals("omitir")) {
                    callback.invoke(origin, false, false);
                } else if (!geoPermShown) {
                    geoPermShown = true;
                    handler.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Permiso de ubicacion")
                        .setMessage("Se usa la ubicación para seleccionar el servidor más cercano.")
                        .setPositiveButton("Permitir", (d, w) -> callback.invoke(origin, true, false))
                        .setNegativeButton("Denegar", (d, w) -> callback.invoke(origin, false, false))
                        .setCancelable(false)
                        .show());
                } else {
                    callback.invoke(origin, true, false);
                }
            }

            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress != 100) return;
                if (phase.equals("speedtest") && !pageLoaded && !goPressed) {
                    pageLoaded = true;
                    handler.postDelayed(MainActivity.this::pressGo, 5000);
                } else if (phase.equals("nperf") && !nPageLoaded && !nGoPressed) {
                    nPageLoaded = true;
                    handler.postDelayed(MainActivity.this::pressNperfGo, 8000);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                if (phase.equals("speedtest") && url.contains("/result/") && !saved.get()) {
                    handler.postDelayed(MainActivity.this::extractMetricsThenSave, 2500);
                } else if (phase.equals("nperf") && url.contains("/result") && !nSaved.get()) {
                    if (nSaved.compareAndSet(false, true)) {
                        handler.postDelayed(MainActivity.this::extractNperfMetrics, 2000);
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                handleWebError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    handleWebError();
                }
            }
        });
    }

    private void handleWebError() {
        AtomicBoolean flag = phase.equals("nperf") ? nErrorDetected : errorDetected;
        if (!flag.compareAndSet(false, true)) return;
        handler.post(() -> {
            if (!isWifiConnected()) showNoWifiDialog();
            else if (!isConnected()) showNoInternetDialog();
            else if (phase.equals("nperf")) retryNperfOrShow("Error de red durante nPerf.");
            else retryRun();
        });
    }

    private void pressGo() {
        if (goPressed || saved.get()) return;
        String url = webView.getUrl();
        if (url == null || !url.contains("speedtest.net")) {
            retryRun();
            return;
        }
        goPressed = true;
        setStatus("Prueba Speedtest en curso...");
        webView.loadUrl("javascript:(function(){" +
            "var sel=['.start-button a','a.js-start-test','.c-go-button'," +
            "'[data-testid=\"start-button\"]','button[class*=\"start\"]'," +
            "'a[class*=\"start\"]','.start-button','#start-button'];" +
            "for(var i=0;i<sel.length;i++){var e=document.querySelector(sel[i]);" +
            "if(e&&e.offsetParent!==null){e.click();return;}}" +
            "var all=document.querySelectorAll('a,button');" +
            "for(var j=0;j<all.length;j++){var t=(all[j].textContent||'').trim();" +
            "if(t==='GO'||t==='Go'){all[j].click();return;}}})()");
        startPolling();
    }

    private void startPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (saved.get()) return;
                pollCount++;
                String status = "Speedtest " + currentRun + "/" + totalRuns + " — " + (pollCount * 3) + "s";
                setStatus(status);
                SpeedtestService.update(MainActivity.this, status,
                    "Prueba " + currentRun + " de " + totalRuns);

                String currentUrl = webView.getUrl();
                if (currentUrl != null && currentUrl.contains("/result/")) {
                    Matcher matcher = Pattern.compile("result/([\\w-]+)").matcher(currentUrl);
                    if (matcher.find() && saved.compareAndSet(false, true)) {
                        resultId = matcher.group(1);
                        resultUrl = currentUrl;
                        extractMetricsThenSave();
                        return;
                    }
                }

                if (!isInBackground && webView != null) {
                    webView.evaluateJavascript(speedtestMetricsScript(), value -> {
                        if (value != null && !value.equals("null")) processData(value);
                    });
                }

                if (!saved.get() && pollCount >= MAX_POLL) {
                    retryRun();
                    return;
                }
                if (!saved.get()) handler.postDelayed(this, 3000);
            }
        }, 8000);
    }

    private String speedtestMetricsScript() {
        return "(function(){" +
            "function g(ss){for(var i=0;i<ss.length;i++){var els=document.querySelectorAll(ss[i]);" +
            "for(var j=0;j<els.length;j++){var e=els[j];var v=e.getAttribute('data-download-speed')||" +
            "e.getAttribute('data-upload-speed')||e.getAttribute('data-latency')||" +
            "e.getAttribute('data-jitter')||e.textContent||'';v=v.trim().replace(/[^0-9.]/g,'');" +
            "var n=parseFloat(v);if(!isNaN(n)&&n>0)return ''+n;}}return '';}" +
            "var url=location.href,m=url.match(/result\\/([\\w-]+)/);" +
            "var b=document.body?document.body.innerHTML:'';" +
            "return JSON.stringify({rid:m?m[1]:'',url:url,isResult:url.indexOf('/result/')>-1," +
            "hasError:/UPLOAD TEST ERROR|DOWNLOAD TEST ERROR|LATENCY TEST ERROR|TEST ERROR/.test(b)," +
            "dl:g(['[data-download-speed]','.download-speed','#download-value'])," +
            "ul:g(['[data-upload-speed]','.upload-speed','#upload-value'])," +
            "pg:g(['[data-latency]','[data-ping]','.ping-speed','#ping-value'])," +
            "jt:g(['[data-jitter]','.jitter-speed','#jitter-value'])});})()";
    }

    private void extractMetricsThenSave() {
        if (webView == null) {
            completeSpeedtestPhase();
            return;
        }
        webView.evaluateJavascript(speedtestMetricsScript(), value -> {
            if (value != null && !value.equals("null")) {
                String json = decodeJavascriptJson(value);
                String dl = key(json, "dl");
                String ul = key(json, "ul");
                String pg = key(json, "pg");
                String jt = key(json, "jt");
                String rid = key(json, "rid");
                String url = key(json, "url");
                if (!dl.isEmpty()) download = dl;
                if (!ul.isEmpty()) upload = ul;
                if (!pg.isEmpty()) ping = pg;
                if (!jt.isEmpty()) jitter = jt;
                if (!rid.isEmpty()) resultId = rid;
                if (!url.isEmpty()) resultUrl = url;
            }
            completeSpeedtestPhase();
        });
    }

    private void processData(String raw) {
        if (saved.get()) return;
        String json = decodeJavascriptJson(raw);
        String rid = key(json, "rid");
        String dl = key(json, "dl");
        String ul = key(json, "ul");
        String pg = key(json, "pg");
        String jt = key(json, "jt");
        String url = key(json, "url");
        boolean isResult = "true".equals(key(json, "isResult"));
        boolean hasError = "true".equals(key(json, "hasError"));

        if (hasError && errorDetected.compareAndSet(false, true)) {
            handler.postDelayed(this::retryRun, 3000);
            return;
        }

        if (!dl.isEmpty()) download = dl;
        if (!ul.isEmpty()) upload = ul;
        if (!pg.isEmpty()) ping = pg;
        if (!jt.isEmpty()) jitter = jt;
        if (!rid.isEmpty()) resultId = rid;
        if (!url.isEmpty()) resultUrl = url;
        handler.post(this::showPanel);

        if (isResult && saved.compareAndSet(false, true)) extractMetricsThenSave();
    }

    private void completeSpeedtestPhase() {
        if (!phase.equals("speedtest")) return;
        saved.set(true);
        phase = "nperf";
        handler.post(() -> {
            showPanel();
            nRetryCount = 0;
            setStatus("Speedtest OK. Iniciando nPerf...");
            handler.postDelayed(this::startNperf, 1500);
        });
    }

    private void startNperf() {
        if (!isWifiConnected()) {
            showNoWifiDialog();
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog();
            return;
        }

        nGoPressed = false;
        nPageLoaded = false;
        nPollCount = 0;
        nDownload = "";
        nUpload = "";
        nPing = "";
        nServer = "";
        nOperator = "";
        nSaved.set(false);
        nErrorDetected.set(false);

        layoutJitter.setVisibility(View.GONE);
        setStatus("Cargando nperf.com...");
        progressBar.setVisibility(View.VISIBLE);
        prepareWebView(true);
        webView.loadUrl("https://www.nperf.com/es/");
        handler.postDelayed(() -> {
            tvResultId.setText("nPerf — midiendo...");
            tvDownload.setText("-");
            tvUpload.setText("-");
            tvPing.setText("-");
            layoutResults.setVisibility(View.VISIBLE);
        }, 500);
    }

    private void pressNperfGo() {
        if (nGoPressed || nSaved.get()) return;
        String url = webView.getUrl();
        if (url == null || !url.contains("nperf.com")) {
            retryNperfOrShow("No se pudo cargar nPerf.");
            return;
        }
        nGoPressed = true;
        setStatus("Iniciando nPerf automáticamente...");

        String script = "javascript:(function(){" +
            "var c=document.querySelector('canvas')||document.querySelector(" +
            "'svg,[class*=gauge],[class*=speedometer],[class*=dial],[class*=meter]');" +
            "if(c){var r=c.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;" +
            "['mousedown','mouseup','click'].forEach(function(t){c.dispatchEvent(new MouseEvent(t," +
            "{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}));});" +
            "}else{var e=document.elementFromPoint(innerWidth/2,innerHeight/2);if(e)e.click();}})()";
        webView.loadUrl(script);
        handler.postDelayed(() -> webView.loadUrl(script), 3000);
        handler.postDelayed(() -> webView.loadUrl(script), 6000);
        startNperfPolling();
    }

    private String nperfMetricsScript() {
        return "(function(){" +
            "var BAD=/average|avg|media|promedio|moyenne|moyen|mean/i;" +
            "function read(ss){for(var i=0;i<ss.length;i++){var es=document.querySelectorAll(ss[i]);" +
            "for(var j=0;j<es.length;j++){var e=es[j];if(!e||e.offsetParent===null)continue;" +
            "var cls=typeof e.className==='string'?e.className:'';" +
            "var meta=(e.id+' '+cls+' '+(e.getAttribute('aria-label')||'')+' '+" +
            "(e.getAttribute('title')||'')+' '+(e.getAttribute('data-testid')||'')+' '+" +
            "(e.textContent||'')).trim();if(BAD.test(meta))continue;" +
            "var nums=(e.textContent||'').replace(/,/g,'.').match(/\\d+(?:\\.\\d+)?/g);" +
            "if(nums&&nums.length){var n=parseFloat(nums[nums.length-1]);if(!isNaN(n)&&n>0)return ''+n;}}}" +
            "return '';}" +
            "var body=(document.body?document.body.innerText:'');" +
            "var done=/Reiniciar|Restart|Reinitier|Volver a probar|Test again|Nuevo test/i.test(body);" +
            "var err=/ERREUR|TEST ERROR|test-error/i.test(body);" +
            "return JSON.stringify({done:done,hasError:err," +
            "dl:read(['[data-testid=\"download-value\"]','[data-testid*=\"download\"][data-testid*=\"value\"]'," +
            "'[data-cy*=\"download\"][data-cy*=\"value\"]','.result-download .value','.download-value'," +
            "'#download-value','[class*=\"download\"][class*=\"result\"] [class*=\"value\"]'," +
            "'[class*=\"download\"][class*=\"value\"]'])," +
            "ul:read(['[data-testid=\"upload-value\"]','[data-testid*=\"upload\"][data-testid*=\"value\"]'," +
            "'[data-cy*=\"upload\"][data-cy*=\"value\"]','.result-upload .value','.upload-value'," +
            "'#upload-value','[class*=\"upload\"][class*=\"result\"] [class*=\"value\"]'," +
            "'[class*=\"upload\"][class*=\"value\"]'])," +
            "pg:read(['[data-testid=\"latency-value\"]','[data-testid*=\"latency\"][data-testid*=\"value\"]'," +
            "'.latency-value','#latency-value','#ping-value','[class*=\"latency\"][class*=\"value\"]'])," +
            "srv:(document.querySelector('[class*=server-name],[class*=isp-name]')||{}).textContent||''," +
            "op:(document.querySelector('[class*=operator],[class*=provider]')||{}).textContent||''});})()";
    }

    private void startNperfPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nSaved.get()) return;
                nPollCount++;
                String status = "nPerf " + currentRun + "/" + totalRuns + " — " + (nPollCount * 3) + "s";
                setStatus(status);
                SpeedtestService.update(MainActivity.this, status,
                    "Prueba " + currentRun + " de " + totalRuns);

                String url = webView.getUrl();
                if (url != null && url.contains("/result") && nSaved.compareAndSet(false, true)) {
                    extractNperfMetrics();
                    return;
                }

                if (webView != null) {
                    webView.evaluateJavascript(nperfMetricsScript(), value -> {
                        if (value != null && !value.equals("null")) processNperfData(value);
                    });
                }

                if (!nSaved.get() && nPollCount >= MAX_NPERF_POLL) {
                    retryNperfOrShow("nPerf superó el tiempo máximo de 4 minutos.");
                    return;
                }
                if (!nSaved.get()) handler.postDelayed(this, 3000);
            }
        }, 8000);
    }

    private void processNperfData(String raw) {
        if (nSaved.get()) return;
        String json = decodeJavascriptJson(raw);
        boolean done = "true".equals(key(json, "done"));
        boolean hasError = "true".equals(key(json, "hasError"));
        String dl = key(json, "dl");
        String ul = key(json, "ul");
        String pg = key(json, "pg");
        String srv = key(json, "srv");
        String op = key(json, "op");

        if (!dl.isEmpty()) nDownload = dl;
        if (!ul.isEmpty()) nUpload = ul;
        if (!pg.isEmpty()) nPing = pg;
        if (!srv.isEmpty()) nServer = srv.trim();
        if (!op.isEmpty()) nOperator = op.trim();
        handler.post(this::showPanel);

        if (hasError && nErrorDetected.compareAndSet(false, true)) {
            retryNperfOrShow("nPerf reportó un error durante la prueba.");
            return;
        }

        if (done && nSaved.compareAndSet(false, true)) {
            extractNperfMetrics();
        }
    }

    private void extractNperfMetrics() {
        if (webView == null) {
            nSaved.set(false);
            retryNperfOrShow("No se pudo leer el resultado final de nPerf.");
            return;
        }
        webView.evaluateJavascript(nperfMetricsScript(), value -> {
            if (value != null && !value.equals("null")) {
                String json = decodeJavascriptJson(value);
                String dl = key(json, "dl");
                String ul = key(json, "ul");
                String pg = key(json, "pg");
                String srv = key(json, "srv");
                String op = key(json, "op");
                if (!dl.isEmpty()) nDownload = dl;
                if (!ul.isEmpty()) nUpload = ul;
                if (!pg.isEmpty()) nPing = pg;
                if (!srv.isEmpty()) nServer = srv.trim();
                if (!op.isEmpty()) nOperator = op.trim();
            }

            if (validMetric(nDownload) && validMetric(nUpload)) {
                setStatus("nPerf completado. Guardando...");
                handler.postDelayed(MainActivity.this::saveTxt, 800);
            } else {
                nSaved.set(false);
                retryNperfOrShow("nPerf terminó, pero no entregó descarga y subida finales válidas.");
            }
        });
    }

    private boolean validMetric(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            return Double.parseDouble(value.replace(',', '.')) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveTxt() {
        if (phase.equals("speedtest")) {
            completeSpeedtestPhase();
            return;
        }

        setStatus("Guardando resultado en Google Drive...");
        String now = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
        String base = "speedtest_run" + currentRun + "_" +
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        String txt =
            "==================================================\n" +
            "   RESULTADO PRUEBAS DE VELOCIDAD - NETLIFE\n" +
            "==================================================\n\n" +
            "  App          : Speedtest NL\n" +
            "  Fecha/Hora   : " + now + "\n" +
            "  Prueba       : " + currentRun + " de " + totalRuns + "\n\n" +
            "-- SPEEDTEST.NET --\n" +
            "  Descarga     : " + f(download, "Mbps") + "\n" +
            "  Subida       : " + f(upload, "Mbps") + "\n" +
            "  Ping         : " + f(ping, "ms") + "\n" +
            "  Jitter       : " + f(jitter, "ms") + "\n" +
            "  Result ID    : " + (resultId.isEmpty() ? "N/A" : resultId) + "\n" +
            "  URL          : " + (resultUrl.isEmpty() ? "N/A" : resultUrl) + "\n\n" +
            "-- NPERF.COM --\n" +
            "  Descarga     : " + f(nDownload, "Mb/s") + "\n" +
            "  Subida       : " + f(nUpload, "Mb/s") + "\n" +
            "  Latencia     : " + f(nPing, "ms") + "\n" +
            "  Servidor     : " + (nServer.isEmpty() ? "N/A" : nServer) + "\n" +
            "  Operador     : " + (nOperator.isEmpty() ? "N/A" : nOperator) + "\n\n" +
            "==================================================\n" +
            "  Speedtest NL - Netlife\n" +
            "==================================================\n";

        String fileName = base + ".txt";
        new Thread(() -> {
            boolean ok = uploadToDrive(fileName, txt);
            handler.post(() -> onRunComplete(ok));
        }).start();
    }

    private boolean uploadToDrive(String fileName, String content) {
        HttpURLConnection conn = null;
        try {
            String jsonBody = "{" +
                "\"fileName\":\"" + fileName + "\"," +
                "\"content\":\"" + content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") + "\"}";
            byte[] body = jsonBody.getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(DRIVE_SCRIPT_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setInstanceFollowRedirects(true);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            br.close();
            return response.toString().contains("ok");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void onRunComplete(boolean success) {
        progressBar.setVisibility(View.GONE);
        showPanel();

        if (!success) {
            showSaveErrorDialog();
            return;
        }

        if (currentRun < totalRuns) {
            String msg = "Prueba " + currentRun + " guardada. Siguiente en " + waitBetween + "s...";
            setStatus(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::startRun, waitBetween * 1000L);
        } else {
            isRunning = false;
            stopBannerWatcher();
            releaseWakeLock();
            SpeedtestService.stop(this);
            setStatus("COMPLETADO: " + totalRuns + " pruebas guardadas");
            showCompletedDialog();
        }
    }

    private void showSaveErrorDialog() {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("No se pudo guardar")
            .setMessage("El resultado está completo, pero no se pudo subir a Google Drive.")
            .setPositiveButton("Reintentar guardado", (d, w) -> saveTxt())
            .setNegativeButton("Restablecer todo", (d, w) -> restartAllTests())
            .setCancelable(false)
            .show());
    }

    private void showCompletedDialog() {
        handler.post(() -> new AlertDialog.Builder(this)
            .setTitle("Pruebas completadas")
            .setMessage("Se guardaron " + totalRuns + " pruebas correctamente.")
            .setPositiveButton("Ejecutar de nuevo", (d, w) -> restartAllTests())
            .setNegativeButton("Cerrar", (d, w) -> finish())
            .setCancelable(false)
            .show());
    }

    private void stopEverything(String status) {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        stopBannerWatcher();
        SpeedtestService.stop(this);
        releaseWakeLock();
        setStatus(status);
        finish();
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return wifi != null && wifi.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            }, PERM_REQ);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_REQ_NOTIF);
        }
    }

    private String decodeJavascriptJson(String value) {
        return value.replaceAll("^\"|\"$", "").replace("\\\"", "\"");
    }

    private String key(String json, String name) {
        Matcher matcher = Pattern.compile(
            "\"" + name + "\":(?:\"([^\"]*)\"|([^,}]*))").matcher(json);
        if (!matcher.find()) return "";
        String quoted = matcher.group(1);
        String raw = matcher.group(2);
        return quoted != null ? quoted : (raw == null ? "" : raw.trim());
    }

    private void showPanel() {
        layoutResults.setVisibility(View.VISIBLE);
        if (phase.equals("nperf")) {
            layoutJitter.setVisibility(View.GONE);
            tvResultId.setText("nPerf — " + (nServer.isEmpty() ? "midiendo..." : nServer));
            tvDownload.setText(nDownload.isEmpty() ? "-" : nDownload + " Mb/s");
            tvUpload.setText(nUpload.isEmpty() ? "-" : nUpload + " Mb/s");
            tvPing.setText(nPing.isEmpty() ? "-" : nPing + " ms");
        } else {
            layoutJitter.setVisibility(View.VISIBLE);
            tvResultId.setText("Result ID: " + (resultId.isEmpty() ? "N/A" : resultId));
            tvDownload.setText(download.isEmpty() ? "-" : download + " Mbps");
            tvUpload.setText(upload.isEmpty() ? "-" : upload + " Mbps");
            tvPing.setText(ping.isEmpty() ? "-" : ping + " ms");
            tvJitter.setText(jitter.isEmpty() ? "-" : jitter + " ms");
        }
    }

    private void setStatus(String message) {
        handler.post(() -> tvStatus.setText(message));
    }

    private String f(String value, String unit) {
        return value == null || value.isEmpty() ? "N/A" : value + " " + unit;
    }
}
