package com.netlife.speedtestnl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
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
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // ── UI ────────────────────────────────────────────────────────────────
    private WebView      webView;
    private TextView     tvStatus, tvResultId, tvDownload, tvUpload;
    private TextView     tvPing, tvJitter, tvCounter;
    private ProgressBar  progressBar;
    private LinearLayout layoutResults;

    // ── Estado prueba ─────────────────────────────────────────────────────
    private String  resultId   = "";
    private String  resultUrl  = "";
    private String  download   = "";
    private String  upload     = "";
    private String  ping       = "";
    private String  jitter     = "";
    private boolean pageLoaded = false;
    private boolean goPressed  = false;
    private final AtomicBoolean saved         = new AtomicBoolean(false);
    private final AtomicBoolean errorDetected = new AtomicBoolean(false);
    private int     pollCount  = 0;

    // ── Estado prueba nperf ───────────────────────────────────────────────
    private String  nDownload  = "";
    private String  nUpload    = "";
    private String  nPing      = "";
    private String  nJitter    = "";
    private String  nServer    = "";
    private String  nOperator  = "";
    private String  nResultId  = "";
    private String  nResultUrl = "";
    private boolean nDone      = false;
    private boolean nGoPressed = false;
    private boolean nPageLoaded= false;
    private int     nPollCount = 0;
    private final AtomicBoolean nSaved         = new AtomicBoolean(false);
    private final AtomicBoolean nErrorDetected = new AtomicBoolean(false);
    private String  phase      = "speedtest"; // fase: speedtest o nperf
    private boolean watcherRunning = false;   // banner watcher activo
    private final AtomicBoolean nperfTransitionStarted = new AtomicBoolean(false);
    private final AtomicBoolean finalSaveStarted = new AtomicBoolean(false);
    private final AtomicBoolean nperfPollingStarted = new AtomicBoolean(false);
    private int nperfRetry = 0;

    // ── Config desde Google Sheets ────────────────────────────────────────
    private int totalRuns    = 3;
    private int maxRetries   = 2;
    private int waitBetween  = 5;
    private int currentRun   = 0;
    private int currentRetry = 0;

    // ── Control de estado global ──────────────────────────────────────────
    private boolean isRunning      = false; // bloquear navegación atrás
    private boolean isInBackground  = false; // app en segundo plano
    private boolean geoPermShown    = false;
    // ubicacion: "preguntar" | "auto" | "omitir" — controlado desde Sheet
    private String  ubicacionMode   = "preguntar";

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
    private NperfAutomation nperfAutomation;
    private int nperfPollingSession = 0;
    private boolean nperfGeckoActive = false;
    private int nperfCompatibilityAttempt = 0;
    private String nperfEngineDiagnostic = "";
    private static final int PERM_REQ          = 100;
    private static final int PERM_REQ_NOTIF    = 101;
    private static final int PERM_REQ_LOCATION = 102;
    private static final int NPERF_GECKO_REQUEST = 202;
    private static final int MAX_POLL       = 120; // 6 minutos

    private static final String SPEEDTEST_URL = "https://www.speedtest.net/en";
    private static final String NPERF_URL = "https://www.nperf.com/es/index";
    private static final String SPEEDTEST_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";
    private static final String NPERF_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0.0.0 Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvStatus      = findViewById(R.id.tvStatus);
        tvResultId    = findViewById(R.id.tvResultId);
        tvDownload    = findViewById(R.id.tvDownload);
        tvUpload      = findViewById(R.id.tvUpload);
        tvPing        = findViewById(R.id.tvPing);
        tvJitter      = findViewById(R.id.tvJitter);
        tvCounter     = findViewById(R.id.tvCounter);
        progressBar   = findViewById(R.id.progressBar);
        layoutResults = findViewById(R.id.layoutResults);
        webView       = findViewById(R.id.webView);

        acquireWakeLock();
        requestPerms();
        setupWebView();
        setupNperfAutomation();
        startForegroundService();

        setStatus("Cargando configuracion...");
        loadConfigFromSheets();
    }

    // ══════════════════════════════════════════════════════════════════════
    // BLOQUEAR BOTÓN ATRÁS durante la prueba
    // ══════════════════════════════════════════════════════════════════════
    @Override
    public void onBackPressed() {
        if (isRunning) {
            // Mostrar aviso en lugar de salir
            new AlertDialog.Builder(this)
                .setTitle("Prueba en curso")
                .setMessage("La prueba esta ejecutandose en segundo plano.\n\n" +
                    "Si sales, la prueba continuara pero la pantalla " +
                    "no se actualizara.\n\n" +
                    "¿Deseas minimizar la app y dejarla correr en segundo plano?")
                .setPositiveButton("Minimizar", (d, w) -> {
                    // Mover app al fondo sin destruirla
                    moveTaskToBack(true);
                })
                .setNegativeButton("Cancelar prueba", (d, w) -> {
                    // Confirmar cancelación
                    new AlertDialog.Builder(this)
                        .setTitle("Confirmar cancelacion")
                        .setMessage("¿Seguro que deseas cancelar todas las pruebas?")
                        .setPositiveButton("Si, cancelar", (d2, w2) -> {
                            isRunning = false;
                            handler.removeCallbacksAndMessages(null);
                            SpeedtestService.stop(this);
                            releaseWakeLock();
                            finish();
                        })
                        .setNegativeButton("No, continuar", (d2, w2) -> {
                            setStatus("Continuando prueba " + currentRun + "...");
                            if (webView != null) webView.resumeTimers();
                        })
                        .show();
                })
                .setCancelable(false)
                .show();
        } else {
            super.onBackPressed();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MANTENER APP EN FONDO — resumeTimers al volver
    // ══════════════════════════════════════════════════════════════════════
    @Override
    protected void onPause() {
        super.onPause();
        isInBackground = true;
        // NUNCA pausar WebView mientras hay una prueba corriendo
        // Pausar solo si no hay prueba activa
        if (webView != null && !isRunning) {
            webView.onPause();
            webView.pauseTimers();
        }
        // Si hay prueba activa, forzar resumeTimers para mantener JS corriendo
        if (webView != null && isRunning) {
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
        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        super.onDestroy();
        releaseWakeLock();
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIG DESDE GOOGLE SHEETS
    // ══════════════════════════════════════════════════════════════════════
    private void loadConfigFromSheets() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(buildSheetsUrl());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new Exception("HTTP " + conn.getResponseCode());

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 2) continue;
                    String k   = parts[0].trim().toLowerCase().replace("\"","");
                    String val = parts[1].trim().replaceAll("[^0-9]","");
                    if (val.isEmpty()) continue;
                    int v = Integer.parseInt(val);
                    // ubicacion es texto — manejar antes del parseo numérico
                    if (k.equals("ubicacion")) {
                        String uv = parts[1].trim()
                            .replace("\"","")
                            .replace("\r","")
                            .replace("\n","")
                            .toLowerCase().trim();
                        if (uv.equals("auto") || uv.equals("omitir") || uv.equals("preguntar")) {
                            ubicacionMode = uv;
                        }
                        continue;
                    }
                    if (val.isEmpty()) continue;
                    switch (k) {
                        case "repeticiones":
                            totalRuns   = Math.max(1, Math.min(50, v)); break;
                        case "reintentos_si_falla":
                            maxRetries  = Math.max(0, Math.min(5,  v)); break;
                        case "espera_entre_pruebas_seg":
                            waitBetween = Math.max(0, Math.min(300,v)); break;
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

    // ══════════════════════════════════════════════════════════════════════
    // FOREGROUND SERVICE
    // ══════════════════════════════════════════════════════════════════════
    private void startForegroundService() {
        Intent i = new Intent(this, SpeedtestService.class);
        i.putExtra(SpeedtestService.EXTRA_STATUS, "Iniciando...");
        i.putExtra(SpeedtestService.EXTRA_PROGRESS, "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WAKELOCK
    // ══════════════════════════════════════════════════════════════════════
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SpeedtestNL::WakeLock");
            wakeLock.acquire(2 * 60 * 60 * 1000L);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INICIAR NUEVA PRUEBA
    // ══════════════════════════════════════════════════════════════════════
    private void startRun() {
        if (!isWifiConnected()) {
            showNoWifiDialog(this::startRun);
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog(this::startRun);
            return;
        }

        isRunning = true;
        currentRun++;
        currentRetry = 0;
        phase = "speedtest";
        resetState();

        String progress = "Prueba " + currentRun + " de " + totalRuns;
        tvCounter.setText(progress);
        setStatus("Iniciando " + currentRun + "/" + totalRuns + "...");
        SpeedtestService.update(this, "Iniciando prueba " + currentRun, progress);

        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
        startBannerWatcher();
        reloadSpeedtestCurrentAttempt();
    }

    private void resetState() {
        resultId = ""; resultUrl = ""; download = ""; upload = "";
        ping = ""; jitter = ""; pageLoaded = false;
        goPressed = false; pollCount = 0;
        saved.set(false);
        errorDetected.set(false);

        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = ""; nResultId = ""; nResultUrl = ""; nDone = false;
        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nSaved.set(false);
        nErrorDetected.set(false);
        nperfPollingStarted.set(false);

        nperfRetry = 0;
        nperfGeckoActive = false;
        nperfCompatibilityAttempt = 0;
        nperfEngineDiagnostic = "";
        nperfTransitionStarted.set(false);
        finalSaveStarted.set(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // BANNER WATCHER — cierra banners/popups automáticamente cada 2s
    // Resiliente a cualquier banner futuro sin necesidad de recompilar
    // ══════════════════════════════════════════════════════════════════════
    private void startBannerWatcher() {
        if (watcherRunning) return;
        watcherRunning = true;

        // JS universal para cerrar banners — múltiples estrategias
        // Se ejecuta cada 2s mientras la prueba esté activa
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning || !watcherRunning || !"speedtest".equals(phase)) {
                    watcherRunning = false;
                    return;
                }

                if (webView != null) {
                    webView.evaluateJavascript(
                        "(function(){" +
                        "var words=['OK','ACEPTAR','ACCEPT','AGREE','ENTENDIDO'," +
                        "  'CONTINUAR','CONTINUER','ALLOW','PERMITIR','GOT IT'," +
                        "  'CERRAR','CLOSE','DISMISS','SKIP','J\'ACCEPTE'];" +

                        // Est. 1: Botón X de cierre en modales (como el de speedtest Windows)
                        "var closeX=document.querySelectorAll(" +
                        "  'button.close,button[aria-label*=close],button[aria-label*=Close]," +
                        "  button[aria-label*=cerrar],[class*=close-btn],[class*=btn-close]," +
                        "  [class*=modal-close],[class*=dialog-close],.close-button," +
                        "  button svg[class*=close],button[title*=close],button[title*=Close]');" +
                        "for(var i=0;i<closeX.length;i++){" +
                        "  if(closeX[i].offsetParent!==null){closeX[i].click();return;}" +
                        "}" +

                        // Est. 2: SVG con X dentro de botón (modal speedtest)
                        "var svgBtns=document.querySelectorAll('button');" +
                        "for(var i=0;i<svgBtns.length;i++){" +
                        "  if(svgBtns[i].offsetParent!==null&&" +
                        "    (svgBtns[i].innerHTML.includes('×')||" +
                        "     svgBtns[i].innerHTML.includes('✕')||" +
                        "     svgBtns[i].innerHTML.includes('✖')||" +
                        "     svgBtns[i].innerHTML.toLowerCase().includes('</svg>')" +
                        "    )&&svgBtns[i].textContent.trim()==='')" +
                        "  {svgBtns[i].click();return;}" +
                        "}" +

                        // Est. 3: Selectores conocidos de banners/cookies
                        "var sel=[" +
                        "  '#didomi-notice-agree-button'," +
                        "  '#onetrust-accept-btn-handler'," +
                        "  '.didomi-btn-agree','.sc-agree-btn'," +
                        "  '[class*=cookie-accept]','[class*=accept-cookie]'," +
                        "  '[id*=accept]','[class*=consent-btn]'" +
                        "];" +
                        "for(var i=0;i<sel.length;i++){" +
                        "  var e=document.querySelector(sel[i]);" +
                        "  if(e&&e.offsetParent!==null){e.click();return;}" +
                        "}" +

                        // Est. 4: Botón por texto dentro de modal/banner/overlay
                        "var btns=document.querySelectorAll('button,a[role=button],.btn');" +
                        "for(var i=0;i<btns.length;i++){" +
                        "  var t=btns[i].textContent.trim().toUpperCase();" +
                        "  var parent=btns[i].closest('[class*=modal],[class*=banner]," +
                        "    [class*=consent],[class*=cookie],[class*=gdpr]," +
                        "    [class*=overlay],[class*=notice],[class*=popup]," +
                        "    [class*=dialog],[class*=alert],[class*=layer]');" +
                        "  if(parent&&words.indexOf(t)>-1&&btns[i].offsetParent!==null)" +
                        "    {btns[i].click();return;}" +
                        "}" +

                        // Est. 5: Cualquier overlay con z-index > 500
                        "var all=document.querySelectorAll('div,section,aside,article');" +
                        "for(var i=0;i<all.length;i++){" +
                        "  var z=parseInt(window.getComputedStyle(all[i]).zIndex)||0;" +
                        "  if(z>500&&all[i].offsetParent!==null){" +
                        "    var btn=all[i].querySelector('button,.btn,a[role=button]');" +
                        "    if(btn&&btn.offsetParent!==null){" +
                        "      var bt=btn.textContent.trim().toUpperCase();" +
                        "      if(words.indexOf(bt)>-1){btn.click();return;}" +
                        "    }" +
                        "    var xbtn=all[i].querySelector('button.close,[class*=close]');" +
                        "    if(xbtn&&xbtn.offsetParent!==null){xbtn.click();return;}" +
                        "  }" +
                        "}" +

                        // Est. 6: ESC como último recurso
                        "document.dispatchEvent(new KeyboardEvent('keydown'," +
                        "  {key:'Escape',keyCode:27,bubbles:true}));" +
                        "})()",
                        null
                    );
                }

                // Continuar mientras la prueba esté activa
                if (isRunning && watcherRunning && "speedtest".equals(phase)) {
                    handler.postDelayed(this, 2000);
                } else {
                    watcherRunning = false;
                }
            }
        }, 2000);
    }

    private void stopBannerWatcher() {
        watcherRunning = false;
    }

    private void showNoWifiDialog() {
        showNoWifiDialog(this::resumeCurrentPhaseAfterConnection);
    }

    private void showNoWifiDialog(Runnable resumeAction) {
        handler.post(() -> {
            setStatus("Requiere conexion WiFi...");
            SpeedtestService.update(this, "Sin WiFi — esperando conexion", "");

            new AlertDialog.Builder(this)
                .setTitle("Conexion WiFi requerida")
                .setMessage("Esta prueba requiere estar conectado a una red WiFi.\n\n" +
                    "Por favor conectese a WiFi y presione Aceptar.")
                .setPositiveButton("Aceptar", (d, w) -> {
                    if (isWifiConnected()) {
                        setStatus("WiFi conectado. Continuando...");
                        handler.postDelayed(resumeAction, 1000);
                    } else {
                        handler.postDelayed(() -> showNoWifiDialog(resumeAction), 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void showNoInternetDialog() {
        showNoInternetDialog(this::resumeCurrentPhaseAfterConnection);
    }

    private void showNoInternetDialog(Runnable resumeAction) {
        handler.post(() -> {
            setStatus("Sin conexion a internet...");
            SpeedtestService.update(this, "Sin datos — esperando conexion", "");

            new AlertDialog.Builder(this)
                .setTitle("Sin Datos")
                .setMessage("No hay conexion a internet.\n\n" +
                    "Verifique su conexion y presione Aceptar para continuar.")
                .setPositiveButton("Aceptar", (d, w) -> {
                    if (isConnected()) {
                        setStatus("Conexion restaurada. Continuando prueba " + currentRun + "...");
                        SpeedtestService.update(this,
                            "Conexion restaurada - continuando prueba " + currentRun,
                            "Prueba " + currentRun + " de " + totalRuns);
                        handler.postDelayed(resumeAction, 1000);
                    } else {
                        handler.postDelayed(() -> showNoInternetDialog(resumeAction), 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void retryRun() {
        if (currentRetry < maxRetries) {
            currentRetry++;
            int runActual = currentRun;
            setStatus("Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")...");
            SpeedtestService.update(this,
                "Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")",
                "Prueba " + runActual + " de " + totalRuns);

            resultId = ""; resultUrl = ""; download = ""; upload = "";
            ping = ""; jitter = ""; pageLoaded = false;
            goPressed = false; pollCount = 0;
            saved.set(false);
            errorDetected.set(false);
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 3000);
        } else {
            showErrorDialog();
        }
    }

    private void showErrorDialog() {
        handler.post(() -> {
            // Detener el Service temporalmente mientras espera respuesta
            SpeedtestService.update(this,
                "Error en prueba " + currentRun + " — esperando decision",
                "Prueba " + currentRun + " de " + totalRuns);

            new AlertDialog.Builder(this)
                .setTitle("Error en prueba " + currentRun)
                .setMessage(
                    "Se produjo un error al ejecutar la prueba " +
                    currentRun + " de " + totalRuns + ".\n\n" +
                    "¿Desea volver a intentarlo?")
                .setPositiveButton("Aceptar", (d, w) -> {
                    // Reiniciar reintentos y volver a la misma prueba
                    currentRetry = 0;
                    resetState();
                    currentRun--;
                    setStatus("Reintentando prueba " + (currentRun + 1) + "...");
                    handler.postDelayed(this::startRun, 3000);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    // Terminar todo — no ejecutar más pruebas
                    isRunning = false;
                    releaseWakeLock();
                    SpeedtestService.stop(this);
                    setStatus("Pruebas canceladas en prueba " + currentRun);
                    Toast.makeText(this,
                        "Pruebas canceladas. Se completaron " +
                        (currentRun - 1) + " de " + totalRuns + " pruebas.",
                        Toast.LENGTH_LONG).show();
                })
                .setCancelable(false) // No se puede cerrar sin elegir
                .show();
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // WEBVIEW — incógnito + geolocalización
    // ══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        // B1: JS habilitado — necesario para ejecutar speedtest.net
        // Riesgo mitigado: se valida dominio speedtest.net antes de inyectar JS (ver pressGo)
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(false);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setGeolocationEnabled(true);
        // User agent escritorio — necesario para prueba completa (descarga + subida)
        s.setUserAgentString(SPEEDTEST_USER_AGENT);
        // Zoom: permitir pellizcar pero sin controles visibles
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Escalar contenido para minimizar zoom inicial
        s.setTextZoom(30);

        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(false);
        cm.removeAllCookies(null);
        cm.flush();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                switch (ubicacionMode) {
                    case "auto":
                        // Aceptar automáticamente sin preguntar
                        callback.invoke(origin, true, false);
                        break;
                    case "omitir":
                        // Denegar sin preguntar
                        callback.invoke(origin, false, false);
                        break;
                    case "preguntar":
                    default:
                        // Mostrar dialog solo la primera vez
                        if (!geoPermShown) {
                            geoPermShown = true;
                            handler.post(() ->
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Permiso de ubicacion")
                                    .setMessage("Speedtest NL usa tu ubicacion " +
                                        "para seleccionar el servidor mas cercano.")
                                    .setPositiveButton("Permitir", (d, w) ->
                                        callback.invoke(origin, true, false))
                                    .setNegativeButton("Denegar", (d, w) ->
                                        callback.invoke(origin, false, false))
                                    .setCancelable(false)
                                    .show()
                            );
                        } else {
                            callback.invoke(origin, true, false);
                        }
                        break;
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String message = consoleMessage == null ? "" : consoleMessage.message();
                String source = consoleMessage == null ? "" : consoleMessage.sourceId();
                int line = consoleMessage == null ? 0 : consoleMessage.lineNumber();
                Log.d("SpeedtestNL-Web", source + ":" + line + " " + message);
                if ("nperf".equals(phase) && isNperfEngineFailureText(message)) {
                    handleNperfEngineFailure(message);
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress == 100) {
                    if (phase.equals("speedtest") && !pageLoaded && !goPressed) {
                        pageLoaded = true;
                        handler.postDelayed(MainActivity.this::pressGo, 5000);
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                if ("speedtest".equals(phase) && url.contains("/result/") && !saved.get()) {
                    handler.postDelayed(() -> completeSpeedtestFromUrl(url), 4000);
                } else if ("nperf".equals(phase) && isNperfResultUrl(url) && !nSaved.get()) {
                    captureNperfResultUrl(url);
                    if (nSaved.compareAndSet(false, true))
                        handler.postDelayed(MainActivity.this::extractNperfMetrics, 4000);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceResponse errorResponse) {
                if (request != null && errorResponse != null) {
                    Log.w("SpeedtestNL-Web", "HTTP " + errorResponse.getStatusCode() +
                        " " + request.getUrl());
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                handleWebViewError();
            }

            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    handleWebViewError();
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRESIONAR GO — con validación de dominio
    // ══════════════════════════════════════════════════════════════════════
    private void pressGo() {
        if (goPressed || saved.get()) return;

        String currentUrl = webView.getUrl();
        if (currentUrl == null || !currentUrl.contains("speedtest.net")) {
            setStatus("Error: dominio inesperado — reintentando...");
            handler.postDelayed(this::retryRun, 2000);
            return;
        }

        goPressed = true;
        setStatus("Iniciando prueba automaticamente...");
        SpeedtestService.update(this,
            "Prueba " + currentRun + " en curso",
            "Prueba " + currentRun + " de " + totalRuns);

        webView.loadUrl("javascript:(function(){" +
            "['#onetrust-accept-btn-handler','button[id*=accept]'," +
            "'button[class*=accept]','button[class*=agree]'].forEach(function(s){" +
            "  var e=document.querySelector(s);if(e)e.click();" +
            "});" +
            "setTimeout(function(){" +
            "  var found=false;" +
            "  var sel=['.start-button a','a.js-start-test','.c-go-button'," +
            "    '[data-testid=\"start-button\"]','button[class*=\"start\"]'," +
            "    'a[class*=\"start\"]','.start-button','#start-button'];" +
            "  for(var i=0;i<sel.length;i++){" +
            "    var el=document.querySelector(sel[i]);" +
            "    if(el&&el.offsetParent!==null){el.click();found=true;break;}" +
            "  }" +
            "  if(!found){" +
            "    var all=document.querySelectorAll('a,button');" +
            "    for(var i=0;i<all.length;i++){" +
            "      var t=all[i].textContent.trim();" +
            "      if(t==='GO'||t==='Go'){all[i].click();break;}" +
            "    }" +
            "  }" +
            "},2000);" +
            "})()");

        setStatus("Prueba en curso...");
        startPolling();
    }

    // ══════════════════════════════════════════════════════════════════════
    // POLLING DOM cada 3s
    // ══════════════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════════════
    // POLLING HÍBRIDO — URL monitoring (fondo) + JS (primer plano)
    // ══════════════════════════════════════════════════════════════════════
    private void startPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (saved.get()) return;
                pollCount++;

                // Watchdog: pausar polling si hay error pendiente
                if (errorDetected.get()) return;

                String statusTxt = "Prueba " + currentRun + "/" +
                                   totalRuns + " — " + (pollCount * 3) + "s";
                setStatus(statusTxt);
                SpeedtestService.update(MainActivity.this, statusTxt,
                    "Prueba " + currentRun + " de " + totalRuns);

                // PASO 1: Monitorear URL — funciona en fondo y primer plano
                String curUrl = webView.getUrl();
                if (curUrl != null && curUrl.contains("/result/")) {
                    completeSpeedtestFromUrl(curUrl);
                    return;
                }

                // PASO 2: JS solo en primer plano
                if (!isInBackground && webView != null) {
                    webView.evaluateJavascript(
                        "(function(){" +
                        "  var url=window.location.href;" +
                        "  var rid='';" +
                        "  var m=url.match(/result\\/([\\w-]+)/);" +
                        "  if(m)rid=m[1];" +
                        "  function getNum(ss){" +
                        "    for(var i=0;i<ss.length;i++){" +
                        "      var els=document.querySelectorAll(ss[i]);" +
                        "      for(var j=0;j<els.length;j++){" +
                        "        var el=els[j];" +
                        "        var v=el.getAttribute('data-download-speed')||" +
                        "              el.getAttribute('data-upload-speed')||" +
                        "              el.getAttribute('data-latency')||" +
                        "              el.getAttribute('data-jitter')||" +
                        "              el.textContent||'';" +
                        "        v=v.trim().replace(/[^0-9.]/g,'');" +
                        "        var n=parseFloat(v);" +
                        "        if(!isNaN(n)&&n>0)return ''+n;" +
                        "      }" +
                        "    }" +
                        "    return '';" +
                        "  }" +
                        "  var dl=getNum(['[data-download-speed]','.download-speed'," +
                        "    '#download-value','[class*=\"download-speed\"]']);" +
                        "  var ul=getNum(['[data-upload-speed]','.upload-speed'," +
                        "    '#upload-value','[class*=\"upload-speed\"]']);" +
                        "  var pg=getNum(['[data-latency]','[data-ping]','.ping-speed'," +
                        "    '#ping-value','[class*=\"latency\"]']);" +
                        "  var jt=getNum(['[data-jitter]','.jitter-speed','#jitter-value']);" +
                        "  var isResult=url.indexOf('/result/')>-1;" +
                        "  var b=document.body?document.body.innerHTML:'';" +
                        "  var hasError=b.indexOf('UPLOAD TEST ERROR')>-1" +
                        "    ||b.indexOf('DOWNLOAD TEST ERROR')>-1" +
                        "    ||b.indexOf('LATENCY TEST ERROR')>-1" +
                        "    ||b.indexOf('TEST ERROR')>-1;" +
                        "  return JSON.stringify({rid:rid,dl:dl,ul:ul,pg:pg,jt:jt," +
                        "    url:url,isResult:isResult,hasError:hasError});" +
                        "})()",
                        value -> {
                            if (value != null && !value.equals("null"))
                                processData(value);
                        }
                    );
                }

                if (!saved.get() && pollCount >= MAX_POLL) {
                    retryRun();
                    return;
                }
                if (!saved.get()) handler.postDelayed(this, 3000);
            }
        }, 8000);
    }

    private void extractMetricsThenStartNperf() {
        if (webView == null) { transitionToNperf(); return; }
        webView.evaluateJavascript(
            "(function(){" +
            "  function g(ss){for(var i=0;i<ss.length;i++){" +
            "    var els=document.querySelectorAll(ss[i]);" +
            "    for(var j=0;j<els.length;j++){" +
            "      var v=els[j].textContent||'';" +
            "      v=v.trim().replace(/[^0-9.]/g,'');" +
            "      var n=parseFloat(v);if(!isNaN(n)&&n>0)return ''+n;" +
            "    }}return '';}" +
            "  return JSON.stringify({" +
            "    dl:g(['[data-download-speed]','.download-speed','#download-value'])," +
            "    ul:g(['[data-upload-speed]','.upload-speed','#upload-value'])," +
            "    pg:g(['[data-latency]','.ping-speed','#ping-value'])," +
            "    jt:g(['[data-jitter]','.jitter-speed','#jitter-value'])" +
            "  });" +
            "})()",
            value -> {
                if (value != null && !value.equals("null")) {
                    try {
                        String v = value.replaceAll("^\"|\"$","");
                        String dl = key(v,"dl"), ul = key(v,"ul");
                        String pg = key(v,"pg"), jt = key(v,"jt");
                        if (!dl.isEmpty()) download = dl;
                        if (!ul.isEmpty()) upload   = ul;
                        if (!pg.isEmpty()) ping     = pg;
                        if (!jt.isEmpty()) jitter   = jt;
                    } catch (Exception e) { e.printStackTrace(); }
                }
                transitionToNperf();
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROCESAR DATOS
    // ══════════════════════════════════════════════════════════════════════
    private void processData(String json) {
        if (saved.get()) return;
        try {
            json = json.replaceAll("^\"|\"$","").replace("\\\"","\"");
            String rid      = key(json,"rid");
            String dl       = key(json,"dl");
            String ul       = key(json,"ul");
            String pg       = key(json,"pg");
            String jt       = key(json,"jt");
            String url      = key(json,"url");
            String isResult = key(json,"isResult");
            String hasError = key(json,"hasError");

            // Detectar error de speedtest.net
            if ("true".equals(hasError) && !saved.get() &&
                    errorDetected.compareAndSet(false, true)) {
                handler.post(() -> {
                    if (!isConnected()) {
                        // Sin internet — mostrar dialog "Sin Datos"
                        setStatus("Sin conexion - esperando internet...");
                        SpeedtestService.update(MainActivity.this,
                            "Sin datos en prueba " + currentRun,
                            "Prueba " + currentRun + " de " + totalRuns);
                        showNoInternetDialog();
                    } else {
                        // Error del servidor — reintentar automáticamente
                        setStatus("Error en prueba " + currentRun +
                            " - reintentando en 5s...");
                        SpeedtestService.update(MainActivity.this,
                            "Error prueba " + currentRun + " - reintentando...",
                            "Prueba " + currentRun + " de " + totalRuns);
                        handler.postDelayed(MainActivity.this::retryRun, 5000);
                    }
                });
                return;
            }

            boolean hasDl = !dl.isEmpty() && !dl.equals("0");
            boolean hasUl = !ul.isEmpty() && !ul.equals("0");

            if (hasDl) {
                download = dl; ping = pg; jitter = jt;
                if (hasUl) upload = ul;
                if (rid != null && !rid.isEmpty()) resultId = rid;
                if (url != null) resultUrl = url;
                // Mostrar valores en tiempo real — forzar visible
                final String dFinal = download;
                final String uFinal = upload;
                final String pFinal = ping;
                final String jFinal = jitter;
                final String rFinal = resultId;
                handler.post(() -> {
                    layoutResults.setVisibility(View.VISIBLE);
                    tvResultId.setText("Result ID: " + (rFinal.isEmpty() ? "N/A" : rFinal));
                    tvDownload.setText(dFinal.isEmpty() ? "-" : dFinal + " Mbps");
                    tvUpload.setText(uFinal.isEmpty()   ? "-" : uFinal + " Mbps");
                    tvPing.setText(pFinal.isEmpty()     ? "-" : pFinal + " ms");
                    tvJitter.setText(jFinal.isEmpty()   ? "-" : jFinal + " ms");
                });
            }

            boolean ready = "true".equals(isResult) ||
                            (!rid.isEmpty() && hasDl && hasUl);

            if (ready) {
                resultId  = rid.isEmpty()  ? resultId  : rid;
                resultUrl = url == null    ? resultUrl : url;
                download  = hasDl ? dl : download;
                upload    = hasUl ? ul : upload;
                ping = pg; jitter = jt;
                completeSpeedtest();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // GUARDAR TXT — I/O en hilo separado
    // ══════════════════════════════════════════════════════════════════════
    private void saveTxt() {
        if (!"nperf".equals(phase) || !nSaved.get()) return;
        if (!finalSaveStarted.compareAndSet(false, true)) return;
        setStatus("Guardando resultado en Google Drive...");

        String now  = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                Locale.getDefault()).format(new Date());
        String base = "speedtest_run" + currentRun + "_" +
                      new SimpleDateFormat("yyyyMMdd_HHmmss",
                          Locale.getDefault()).format(new Date());

        String txt =
            "==================================================\n" +
            "   RESULTADO PRUEBAS DE VELOCIDAD - NETLIFE\n" +
            "==================================================\n\n" +
            "  App          : Speedtest NL\n" +
            "  Fecha/Hora   : " + now + "\n" +
            "  Prueba       : " + currentRun + " de " + totalRuns + "\n\n" +
            "-- SPEEDTEST.NET --\n" +
            "  Descarga     : " + f(download,"Mbps") + "\n" +
            "  Subida       : " + f(upload,  "Mbps") + "\n" +
            "  Ping         : " + f(ping,    "ms")   + "\n" +
            "  Jitter       : " + f(jitter,  "ms")   + "\n" +
            "  Result ID    : " + (resultId.isEmpty()  ? "N/A" : resultId)  + "\n" +
            "  URL          : " + (resultUrl.isEmpty() ? "N/A" : resultUrl) + "\n\n" +
            "-- NPERF.COM --\n" +
            "  Descarga     : " + f(nDownload,"Mb/s") + "\n" +
            "  Subida       : " + f(nUpload,  "Mb/s") + "\n" +
            "  Latencia     : " + f(nPing,    "ms")   + "\n" +
            "  Jitter       : " + f(nJitter,  "ms")   + "\n" +
            "  Servidor     : " + (nServer.isEmpty()   ? "N/A" : nServer)   + "\n" +
            "  Operador     : " + (nOperator.isEmpty() ? "N/A" : nOperator) + "\n" +
            "  Result ID    : " + (nResultId.isEmpty() ? "N/A" : nResultId) + "\n" +
            "  URL          : " + (nResultUrl.isEmpty() ? "N/A" : nResultUrl) + "\n\n" +
            "==================================================\n" +
            "  Speedtest NL - Netlife\n" +
            "==================================================\n";

        String finalTxt = txt;
        String fileName = base + ".txt";

        new Thread(() -> {
            boolean ok = uploadToDrive(fileName, finalTxt);
            handler.post(() -> onRunComplete(ok));
        }).start();
    }

    private boolean uploadToDrive(String fileName, String content) {
        java.net.HttpURLConnection conn = null;
        try {
            String jsonBody = "{" +
                "\"fileName\":\"" + fileName + "\"," +
                "\"content\":\"" + content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") +
                "\"" +
            "}";

            byte[] body = jsonBody.getBytes("UTF-8");
            java.net.URL url = new java.net.URL(DRIVE_SCRIPT_URL);
            conn = (java.net.HttpURLConnection) url.openConnection();
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
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String response = sb.toString();
            boolean success = response.contains("ok");

            handler.post(() -> setStatus(success
                ? "Guardado en Google Drive: " + fileName
                : "Error al subir a Drive"));

            return success;

        } catch (Exception e) {
            e.printStackTrace();
            handler.post(() -> setStatus("Error conexion Drive: " + e.getMessage()));
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }



    // ══════════════════════════════════════════════════════════════════════
    // NPERF EN GECKOVIEW — motor Firefox integrado
    // ══════════════════════════════════════════════════════════════════════
    private void startNperfGecko() {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (nperfGeckoActive) return;
        if (!isWifiConnected()) { showNoWifiDialog(this::startNperfGecko); return; }
        if (!isConnected()) { showNoInternetDialog(this::startNperfGecko); return; }

        nperfGeckoActive = true;
        nErrorDetected.set(false);
        nPageLoaded = true;
        nGoPressed = true;
        nPollCount = 0;
        nperfPollingSession++;
        nperfPollingStarted.set(false);
        if (nperfAutomation != null) nperfAutomation.cancel();
        if (webView != null) webView.stopLoading();

        setStatus("Abriendo nPerf en GeckoView...");
        SpeedtestService.update(this,
            "nPerf GeckoView - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);

        Intent intent = new Intent(this, NperfGeckoActivity.class);
        intent.putExtra(NperfGeckoActivity.EXTRA_RUN, currentRun);
        intent.putExtra(NperfGeckoActivity.EXTRA_TOTAL_RUNS, totalRuns);
        // El sitio nPerf recibe permiso automático dentro de GeckoView. Android
        // muestra su diálogo del sistema una sola vez cuando aún no fue concedido.
        intent.putExtra(NperfGeckoActivity.EXTRA_LOCATION_MODE, "auto");
        startActivityForResult(intent, NPERF_GECKO_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != NPERF_GECKO_REQUEST) return;

        nperfGeckoActive = false;
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;

        if (resultCode == RESULT_OK && data != null) {
            nDownload = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_DOWNLOAD));
            nUpload = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_UPLOAD));
            nPing = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_LATENCY));
            nJitter = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_JITTER));
            nServer = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_SERVER));
            nOperator = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_OPERATOR));
            nResultId = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_RESULT_ID));
            nResultUrl = valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_RESULT_URL));

            if (nDownload.isEmpty() || nUpload.isEmpty()) {
                setStatus("nPerf GeckoView devolvió un resultado incompleto.");
                handler.postDelayed(this::retryNperf, 1200L);
                return;
            }

            if (nSaved.compareAndSet(false, true)) {
                nDone = true;
                progressBar.setVisibility(View.GONE);
                showPanel();
                setStatus("nPerf GeckoView completado. Guardando...");
                SpeedtestService.update(this,
                    "nPerf completado - prueba " + currentRun,
                    "Prueba " + currentRun + " de " + totalRuns);
                handler.postDelayed(this::saveTxt, 1000L);
            }
            return;
        }

        String code = data == null ? "GECKO_CANCELLED" :
            valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_ERROR_CODE));
        String detail = data == null ? "La actividad nPerf terminó sin resultado" :
            valueOrEmpty(data.getStringExtra(NperfGeckoActivity.EXTRA_ERROR_DETAIL));
        if (detail.isEmpty()) detail = "nPerf no devolvió un resultado válido";

        setStatus("Error nPerf GeckoView: " + detail);
        SpeedtestService.update(this,
            "Error nPerf GeckoView " + code,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::retryNperf, 1500L);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void setupNperfAutomation() {
        nperfAutomation = new NperfAutomation(webView, handler,
            new NperfAutomation.Listener() {
                @Override
                public void onStatus(String message) {
                    if ("nperf".equals(phase) && !nSaved.get()) setStatus(message);
                }

                @Override
                public void onStartTouchSent() {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    setStatus("nperf: toque enviado; esperando respuesta...");
                    SpeedtestService.update(MainActivity.this,
                        "nperf esperando resultados - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                }

                @Override
                public void onManualStartAvailable() {
                    if (!"nperf".equals(phase) || nSaved.get()) return;
                    setStatus("nperf no inició automáticamente. Puede tocar Iniciar test; no se recargará.");
                    SpeedtestService.update(MainActivity.this,
                        "nperf esperando inicio manual - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                }

                @Override
                public void onEngineError(String message) {
                    handleNperfEngineFailure(message);
                }
            });
    }

    private boolean isNperfEngineFailureText(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("no fue posible inicializar") ||
            lower.contains("no se pudo inicializar") ||
            lower.contains("error al inicializar") ||
            lower.contains("unable to initialize") ||
            lower.contains("could not initialize") ||
            lower.contains("initialization failed") ||
            lower.contains("impossible d'initialiser");
    }

    private void handleNperfEngineFailure(String message) {
        if (!"nperf".equals(phase) || nSaved.get() || finalSaveStarted.get()) return;
        if (!nErrorDetected.compareAndSet(false, true)) return;

        nperfEngineDiagnostic = message == null ? "" : message.trim();
        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        nperfPollingStarted.set(false);

        if (nperfCompatibilityAttempt == 0) {
            nperfCompatibilityAttempt = 1;
            setStatus("nperf no inicializó. Aplicando modo compatible...");
            SpeedtestService.update(this,
                "nperf: reintentando inicialización compatible",
                "Prueba " + currentRun + " de " + totalRuns);
            handler.postDelayed(this::reloadNperfCompatibilityMode, 1400L);
        } else {
            String detail = nperfEngineDiagnostic.isEmpty()
                ? "motor no disponible" : nperfEngineDiagnostic;
            setStatus("nperf no pudo inicializar: " + detail);
            SpeedtestService.update(this,
                "nperf no pudo inicializar - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);
        }
    }

    private void reloadNperfCompatibilityMode() {
        if (!"nperf".equals(phase) || nSaved.get() || webView == null) return;
        nErrorDetected.set(false);
        nGoPressed = false;
        nPageLoaded = false;
        nPollCount = 0;
        nperfPollingStarted.set(false);
        applyNperfWebProfile(true);
        webView.stopLoading();
        webView.loadUrl(NPERF_URL + "?stnl=" + System.currentTimeMillis());
    }

    // ══════════════════════════════════════════════════════════════════════
    // NPERF — Iniciar prueba
    // ══════════════════════════════════════════════════════════════════════
    private void startNperf() {
        if (!isWifiConnected()) { showNoWifiDialog(); return; }
        if (!isConnected())     { showNoInternetDialog(); return; }

        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        nperfCompatibilityAttempt = 0;
        nperfEngineDiagnostic = "";
        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = ""; nResultId = ""; nResultUrl = "";
        nSaved.set(false); nErrorDetected.set(false);
        nperfPollingStarted.set(false);

        setStatus("Cargando nperf.com...");
        progressBar.setVisibility(View.VISIBLE);

        prepareNperfSession();
        applyNperfWebProfile(false);
        webView.loadUrl(NPERF_URL);
        handler.postDelayed(() -> {
            tvResultId.setText("nperf — midiendo...");
            tvDownload.setText("-");
            tvUpload.setText("-");
            tvPing.setText("-");
            tvJitter.setText("-");
            layoutResults.setVisibility(View.VISIBLE);
        }, 500);
    }

    // ── Presionar "Iniciar test" en nperf ─────────────────────────────────
    private void pressNperfGo() {
        if (nGoPressed || nSaved.get()) return;

        String curUrl = webView == null ? null : webView.getUrl();
        if (curUrl == null || !curUrl.contains("nperf.com")) {
            setStatus("nperf todavía cargando; esperando sin recargar...");
            handler.postDelayed(this::pressNperfGo, 1500);
            return;
        }

        nGoPressed = true;
        setStatus("Preparando automatización nperf...");
        if (nperfAutomation != null) {
            nperfAutomation.begin();
        } else {
            nGoPressed = false;
            setStatus("Controlador nperf no disponible.");
        }
    }

    private String decodeJsResult(String value) {
        try {
            Object decoded = new org.json.JSONTokener(
                value == null ? "null" : value).nextValue();
            if (decoded instanceof String) return (String) decoded;
            return decoded == null ? "" : decoded.toString();
        } catch (Exception error) {
            return value == null ? "" : value
                .replaceAll("^\"|\"$", "")
                .replace("\\\"", "\"");
        }
    }

    private boolean isNperfResultUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("nperf.com") &&
            (lower.contains("/r/") || lower.contains("/result"));
    }

    private void captureNperfResultUrl(String url) {
        if (url == null || url.isEmpty()) return;
        nResultUrl = url;
        Matcher matcher = Pattern.compile("/r/(\\d+)(?:-|/|$)").matcher(url);
        if (matcher.find()) nResultId = matcher.group(1);
    }

// ── Polling nperf cada 3s ─────────────────────────────────────────────
    private void startNperfPolling() {
        if (!nperfPollingStarted.compareAndSet(false, true)) return;
        final int pollingSession = nperfPollingSession;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pollingSession != nperfPollingSession || nSaved.get()) return;
                nPollCount++;

                String statusTxt = "nperf prueba " + currentRun + "/" +
                    totalRuns + " — " + (nPollCount * 3) + "s";
                setStatus(statusTxt);
                SpeedtestService.update(MainActivity.this, statusTxt,
                    "Prueba " + currentRun + " de " + totalRuns);

                // Monitorear URL — nperf cambia URL al terminar
                String curUrl = webView.getUrl();
                if (isNperfResultUrl(curUrl)) {
                    captureNperfResultUrl(curUrl);
                    if (nSaved.compareAndSet(false, true)) {
                        extractNperfMetrics();
                        return;
                    }
                }

                if (!isInBackground && webView != null) {
                    webView.evaluateJavascript(
                        "(function(){" +
                        "  function g(ss){" +
                        "    for(var i=0;i<ss.length;i++){" +
                        "      var els=document.querySelectorAll(ss[i]);" +
                        "      for(var j=0;j<els.length;j++){" +
                        "        var v=els[j].textContent.trim();" +
                        "        var n=parseFloat(v.replace(/[^0-9.]/g,''));" +
                        "        if(!isNaN(n)&&n>0)return ''+n;" +
                        "      }" +
                        "    }" +
                        "    return '';" +
                        "  }" +
                        "  var b=document.body?document.body.innerHTML:'';" +
                        "  var bt=(document.body?document.body.innerText:'').toLowerCase();" +
                        "  var hasError=b.indexOf('ERREUR')>-1||b.indexOf('ERROR')>-1" +
                        "    ||b.indexOf('error')>-1&&b.indexOf('test-error')>-1;" +
                        "  var done=bt.indexOf('haz click aquí para probar de nuevo')>-1" +
                        "    ||bt.indexOf('haz clic aquí para probar de nuevo')>-1" +
                        "    ||bt.indexOf('reiniciar')>-1||bt.indexOf('restart')>-1" +
                        "    ||bt.indexOf('reinitier')>-1;" +
                        "  var dl=g(['.download-value','#download-value'," +
                        "    '[class*=download][class*=value]','[id*=download]'," +
                        "    '.result-download']);" +
                        "  var ul=g(['.upload-value','#upload-value'," +
                        "    '[class*=upload][class*=value]','[id*=upload]'," +
                        "    '.result-upload']);" +
                        "  var pg=g(['.latency-value','#latency-value','#ping-value'," +
                        "    '[class*=latency]','[class*=ping]']);" +
                        "  var jt=g(['.jitter-value','#jitter-value'," +
                        "    '[class*=jitter]']);" +
                        "  var srv=(document.querySelector('[class*=server-name]," +
                        "    [class*=isp-name],[class*=server]')||{}).textContent||'';" +
                        "  var op=(document.querySelector('[class*=operator]," +
                        "    [class*=isp],[class*=provider]')||{}).textContent||'';" +
                        "  return JSON.stringify({done:done,hasError:hasError," +
                        "    dl:dl,ul:ul,pg:pg,jt:jt,srv:srv,op:op});" +
                        "})()",
                        value -> {
                            if (value != null && !value.equals("null"))
                                processNperfData(value);
                        }
                    );
                }

                if (!nSaved.get() && nPollCount == 40) {
                    setStatus("nperf sigue sin resultados. Puede iniciar manualmente; no se recargará.");
                }
                if (!nSaved.get() && nPollCount >= MAX_POLL) {
                    setStatus("nperf sin respuesta. La página se mantiene abierta para inicio manual.");
                    handler.postDelayed(this, 8000);
                    return;
                }
                if (!nSaved.get() && pollingSession == nperfPollingSession)
                    handler.postDelayed(this, 3000);
            }
        }, 8000);
    }

    // ── Procesar datos de nperf ───────────────────────────────────────────
    private void processNperfData(String json) {
        if (nSaved.get()) return;
        try {
            json = json.replaceAll("^\"|\"$","").replace("\\\"","\"");
            String done     = key(json,"done");
            String hasError = key(json,"hasError");
            String dl       = key(json,"dl");
            String ul       = key(json,"ul");
            String pg       = key(json,"pg");
            String jt       = key(json,"jt");
            String srv      = key(json,"srv");
            String op       = key(json,"op");

            // Actualizar valores parciales en tiempo real
            boolean updated = false;
            if (dl  != null && !dl.isEmpty())  { nDownload  = dl;         updated = true; }
            if (ul  != null && !ul.isEmpty())  { nUpload    = ul;         updated = true; }
            if (pg  != null && !pg.isEmpty())  { nPing      = pg;         updated = true; }
            if (jt  != null && !jt.isEmpty())  { nJitter    = jt;         updated = true; }
            if (srv != null && !srv.isEmpty()) { nServer    = srv.trim(); updated = true; }
            if (op  != null && !op.isEmpty())  { nOperator  = op.trim();  updated = true; }
            // Mostrar en panel en tiempo real
            if (updated) handler.post(this::showPanel);

            // Error detectado
            if ("true".equals(hasError)) {
                handler.post(() -> {
                    if (!isConnected()) showNoInternetDialog();
                    else handleNperfEngineFailure("nPerf reportó un error de inicialización");
                });
                return;
            }

            // Prueba terminada — "Reiniciar" visible O tenemos DL+UL
            boolean hasDl   = !nDownload.isEmpty() && !nDownload.equals("0");
            boolean hasUl   = !nUpload.isEmpty()   && !nUpload.equals("0");
            boolean finished = "true".equals(done) && hasDl && hasUl;

            if (finished && nSaved.compareAndSet(false, true)) {
                handler.post(() -> {
                    setStatus("nperf completado. Guardando...");
                    handler.postDelayed(MainActivity.this::saveTxt, 2000);
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Extraer métricas nperf via JS (cuando URL cambia) ─────────────────
    private void extractNperfMetrics() {
        if (webView == null) { saveTxt(); return; }
        webView.evaluateJavascript(
            "(function(){try{" +
            "var lines=(document.body?document.body.innerText:'').split(/\\n+/)" +
            ".map(function(s){return s.trim();}).filter(function(s){return s.length>0;});" +
            "function norm(s){return (s||'').toLowerCase().replace(/:$/,'').trim();}" +
            "function metric(labels){for(var i=0;i<lines.length;i++){var n=norm(lines[i]);" +
            "for(var l=0;l<labels.length;l++){if(n===labels[l]||n.indexOf(labels[l])===0){" +
            "for(var j=i+1;j<Math.min(lines.length,i+7);j++){var v=lines[j];" +
            "if(/average|promedio|media/.test(norm(v)))continue;" +
            "var m=v.match(/^([0-9]+(?:[.,][0-9]+)?)$/)||" +
            "v.match(/([0-9]+(?:[.,][0-9]+)?)\\s*(?:mb\\/s|ms)/i);" +
            "if(m)return m[1].replace(',','.');}}}}return ''; }" +
            "function jitter(){for(var i=0;i<lines.length;i++){" +
            "var m=lines[i].match(/jitter\\s*[:]?\\s*([0-9]+(?:[.,][0-9]+)?)/i);" +
            "if(m)return m[1].replace(',','.');}return ''; }" +
            "function after(labels){for(var i=0;i<lines.length;i++){var n=norm(lines[i]);" +
            "for(var l=0;l<labels.length;l++){if(n===labels[l]){" +
            "for(var j=i+1;j<Math.min(lines.length,i+5);j++){var v=lines[j];" +
            "if(v&&!/^(mb\\/s|ms)$/i.test(v)&&!/^average/i.test(v))return v;}}}}return ''; }" +
            "return JSON.stringify({" +
            "dl:metric(['download','descarga','velocidad de descarga'])," +
            "ul:metric(['upload','subida','velocidad de subida'])," +
            "pg:metric(['latency','latencia','ping'])," +
            "jt:jitter()," +
            "op:after(['connection','conexión','conexion'])," +
            "srv:after(['server','servidor'])" +
            "});}catch(e){return JSON.stringify({error:e.message});}})()",
            value -> {
                String json = decodeJsResult(value);
                String dl = key(json,"dl"), ul = key(json,"ul");
                String pg = key(json,"pg"), jt = key(json,"jt");
                String srv = key(json,"srv"), op = key(json,"op");
                if (!dl.isEmpty())  nDownload  = dl;
                if (!ul.isEmpty())  nUpload    = ul;
                if (!pg.isEmpty())  nPing      = pg;
                if (!jt.isEmpty())  nJitter    = jt;
                if (!srv.isEmpty()) nServer    = srv.trim();
                if (!op.isEmpty())  nOperator  = op.trim();
                handler.post(this::showPanel);
                handler.postDelayed(MainActivity.this::saveTxt, 1000);
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // COMPLETAR SPEEDTEST — iniciar nperf antes de guardar
    // ══════════════════════════════════════════════════════════════════════
    private void onRunComplete(boolean success) {
        progressBar.setVisibility(View.GONE);
        showPanel();

        if (currentRun < totalRuns) {
            String msg = "Prueba " + currentRun +
                (success ? " guardada." : " fallida.") +
                " Siguiente en " + waitBetween + "s...";
            setStatus(msg);
            SpeedtestService.update(this, msg,
                "Prueba " + currentRun + " de " + totalRuns);
            Toast.makeText(this,
                "Prueba " + currentRun +
                (success ? " guardada." : " con error de guardado.") +
                " Siguiente en " + waitBetween + "s...",
                Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::startRun, waitBetween * 1000L);
        } else {
            isRunning = false;
            stopBannerWatcher();
            releaseWakeLock();
            SpeedtestService.stop(this);
            setStatus(success
                ? "COMPLETADO: " + totalRuns + " pruebas guardadas"
                : "COMPLETADO con error al guardar la ultima prueba");
            Toast.makeText(this,
                "Todas las pruebas completadas (" + totalRuns + ")",
                Toast.LENGTH_LONG).show();
        }
    }

    private void setSpeedtestUserAgent() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(SPEEDTEST_USER_AGENT);
        settings.setTextZoom(30);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
    }

    private String buildNperfDesktopUserAgent(boolean compatibilityMode) {
        String defaultUa = WebSettings.getDefaultUserAgent(this);
        Matcher chrome = Pattern.compile("Chrome/([0-9.]+)").matcher(defaultUa);
        String version = chrome.find() ? chrome.group(1) : "131.0.0.0";
        String platform = compatibilityMode
            ? "X11; Linux x86_64" : "Windows NT 10.0; Win64; x64";
        return "Mozilla/5.0 (" + platform + ") " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version +
            " Safari/537.36";
    }

    private void applyNperfWebProfile(boolean compatibilityMode) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(buildNperfDesktopUserAgent(compatibilityMode));
        settings.setTextZoom(100);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        android.webkit.CookieManager cookies =
            android.webkit.CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }
        cookies.flush();

        if (compatibilityMode) webView.clearCache(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.pm.PackageInfo webViewPackage =
                    WebView.getCurrentWebViewPackage();
                if (webViewPackage != null) {
                    Log.i("SpeedtestNL-Web", "WebView " + webViewPackage.packageName +
                        " " + webViewPackage.versionName +
                        " UA=" + settings.getUserAgentString());
                }
            } catch (Exception ignored) { }
        }
    }

    private void setNperfUserAgent() {
        applyNperfWebProfile(false);
    }

    private void clearWebViewSession(boolean acceptCookies) {
        if (webView == null) return;
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(acceptCookies);
        cm.removeAllCookies(null);
        cm.flush();
    }

    private void prepareNperfSession() {
        if (webView == null) return;
        webView.stopLoading();
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }
        cm.flush();
    }

    private void reloadSpeedtestCurrentAttempt() {
        if (webView == null) return;
        if (nperfAutomation != null) nperfAutomation.cancel();
        nperfPollingSession++;
        phase = "speedtest";
        pageLoaded = false;
        goPressed = false;
        pollCount = 0;
        saved.set(false);
        errorDetected.set(false);
        setSpeedtestUserAgent();
        clearWebViewSession(false);
        webView.loadUrl(SPEEDTEST_URL);
        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
    }

    private void completeSpeedtestFromUrl(String url) {
        if (url != null) {
            Matcher matcher = Pattern.compile("result/([\\w-]+)").matcher(url);
            if (matcher.find()) resultId = matcher.group(1);
            resultUrl = url;
        }
        completeSpeedtest();
    }

    private void completeSpeedtest() {
        if (!"speedtest".equals(phase) || !saved.compareAndSet(false, true)) return;
        handler.post(() -> {
            showPanel();
            if (!isInBackground && webView != null) {
                extractMetricsThenStartNperf();
            } else {
                transitionToNperf();
            }
        });
    }

    private void transitionToNperf() {
        if (!nperfTransitionStarted.compareAndSet(false, true)) return;
        stopBannerWatcher();
        phase = "nperf";
        nperfRetry = 0;
        setStatus("Speedtest OK. Iniciando nperf...");
        SpeedtestService.update(this,
            "Iniciando nperf - prueba " + currentRun,
            "Prueba " + currentRun + " de " + totalRuns);
        handler.postDelayed(this::startNperfGecko, 1200);
    }

    private void resumeCurrentPhaseAfterConnection() {
        if ("nperf".equals(phase)) {
            nErrorDetected.set(false);
            handler.postDelayed(this::startNperfGecko, 1000);
        } else {
            errorDetected.set(false);
            handler.postDelayed(this::reloadSpeedtestCurrentAttempt, 1000);
        }
    }

    private void retryNperf() {
        if (nSaved.get() || finalSaveStarted.get()) return;
        if (nperfRetry < maxRetries) {
            nperfRetry++;
            nErrorDetected.set(false);
            setStatus("Reintentando nperf (" + nperfRetry + "/" + maxRetries + ")...");
            SpeedtestService.update(this,
                "Reintentando nperf - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);
            handler.postDelayed(this::startNperfGecko, 3000);
        } else {
            showErrorDialog();
        }
    }

    private void handleWebViewError() {
        boolean nperfPhase = "nperf".equals(phase);
        AtomicBoolean completed = nperfPhase ? nSaved : saved;
        AtomicBoolean errorFlag = nperfPhase ? nErrorDetected : errorDetected;
        if (completed.get() || !errorFlag.compareAndSet(false, true)) return;

        handler.post(() -> {
            if (!isWifiConnected()) {
                showNoWifiDialog();
            } else if (!isConnected()) {
                showNoInternetDialog();
            } else {
                setStatus("Error de red - reintentando...");
                if (nperfPhase) retryNperf();
                else retryRun();
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILIDADES
    // ══════════════════════════════════════════════════════════════════════
    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                return caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo wifi = cm.getNetworkInfo(
                    ConnectivityManager.TYPE_WIFI);
                return wifi != null && wifi.isConnected();
            }
        } catch (Exception e) { return false; }
    }

    private boolean isConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                return caps != null && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) { return true; }
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }, PERM_REQ);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERM_REQ_NOTIF);
            }
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERM_REQ_LOCATION);
        }
    }

    private String key(String json, String k) {
        Matcher m = Pattern.compile(
            "\""+k+"\":(?:\"([^\"]*)\"|([^,}]*))").matcher(json);
        if (m.find()) {
            String g1 = m.group(1), g2 = m.group(2);
            return g1 != null ? g1 : (g2 != null ? g2.trim() : "");
        }
        return "";
    }

    private void showPanel() {
        layoutResults.setVisibility(View.VISIBLE);
        if (phase.equals("nperf")) {
            // Mostrar valores de nperf
            tvResultId.setText(!nResultId.isEmpty()
                ? "nPerf ID: " + nResultId
                : "nperf — " + (nServer.isEmpty() ? "midiendo..." : nServer));
            tvDownload.setText(nDownload.isEmpty() ? "-" : nDownload + " Mb/s");
            tvUpload.setText(nUpload.isEmpty()     ? "-" : nUpload   + " Mb/s");
            tvPing.setText(nPing.isEmpty()         ? "-" : nPing     + " ms");
            tvJitter.setText(nJitter.isEmpty()     ? "-" : nJitter   + " ms");
        } else {
            // Mostrar valores de speedtest
            tvResultId.setText("Result ID: " + (resultId.isEmpty() ? "N/A" : resultId));
            tvDownload.setText(download.isEmpty() ? "-" : download + " Mbps");
            tvUpload.setText(upload.isEmpty()     ? "-" : upload   + " Mbps");
            tvPing.setText(ping.isEmpty()         ? "-" : ping     + " ms");
            tvJitter.setText(jitter.isEmpty()     ? "-" : jitter   + " ms");
        }
    }

    private void setStatus(String m) { handler.post(() -> tvStatus.setText(m)); }

    // ── DEBUG: muestra HTML de la página actual ────────────────────────────
    private String f(String v, String u) {
        return (v == null || v.isEmpty()) ? "N/A" : v + " " + u;
    }
}
