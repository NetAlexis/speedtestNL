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
    private boolean nDone      = false;
    private boolean nGoPressed = false;
    private boolean nPageLoaded= false;
    private int     nPollCount = 0;
    private final AtomicBoolean nSaved         = new AtomicBoolean(false);
    private final AtomicBoolean nErrorDetected = new AtomicBoolean(false);
    private String  phase      = "speedtest"; // fase: speedtest o nperf
    private boolean watcherRunning = false;   // banner watcher activo

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
    private static final int PERM_REQ       = 100;
    private static final int PERM_REQ_NOTIF = 101;
    private static final int MAX_POLL       = 120; // 6 minutos

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
                            // Retomar la prueba donde se quedó
                            setStatus("Retomando prueba " + currentRun + "...");
                            errorDetected.set(false);
                            saved.set(false);
                            resultId = ""; resultUrl = ""; download = "";
                            upload = ""; ping = ""; jitter = "";
                            pageLoaded = false; goPressed = false; pollCount = 0;
                            handler.postDelayed(() -> {
                                webView.clearCache(true);
                                webView.clearHistory();
                                webView.clearFormData();
                                android.webkit.CookieManager.getInstance()
                                    .removeAllCookies(null);
                                // speedtest requiere user agent escritorio para prueba completa
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36");

        webView.loadUrl("https://www.speedtest.net/en");
        // Iniciar banner watcher para toda la duración de la prueba
        startBannerWatcher();
        // Mostrar panel vacío desde el inicio
        handler.postDelayed(() -> {
            layoutResults.setVisibility(View.VISIBLE);
        }, 1000);
                                progressBar.setVisibility(View.VISIBLE);
                            }, 2000);
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
        // Validar WiFi obligatorio primero
        if (!isWifiConnected()) {
            showNoWifiDialog();
            return;
        }
        if (!isConnected()) {
            showNoInternetDialog();
            return;
        }

        isRunning = true; // activar bloqueo de navegación
        currentRun++;
        currentRetry = 0;
        phase = "speedtest"; // siempre empieza con speedtest
        resetState();

        String progress = "Prueba " + currentRun + " de " + totalRuns;
        tvCounter.setText(progress);
        setStatus("Iniciando " + currentRun + "/" + totalRuns + "...");
        SpeedtestService.update(this, "Iniciando prueba " + currentRun, progress);

        progressBar.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);

        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(false);
        cm.removeAllCookies(null);
        cm.flush();

        webView.loadUrl("https://www.speedtest.net/en");
    }

    private void resetState() {
        // Reset speedtest
        resultId = ""; resultUrl = ""; download = ""; upload = "";
        ping = ""; jitter = ""; pageLoaded = false;
        goPressed = false; pollCount = 0;
        saved.set(false);
        errorDetected.set(false);
        // Reset nperf
        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = ""; nDone = false;
        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nSaved.set(false);
        nErrorDetected.set(false);
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
                if (!isRunning) {
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
                if (isRunning) handler.postDelayed(this, 2000);
                else watcherRunning = false;
            }
        }, 2000);
    }

    private void stopBannerWatcher() {
        watcherRunning = false;
    }

    private void showNoWifiDialog() {
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
                        handler.postDelayed(this::startRun, 1000);
                    } else {
                        handler.postDelayed(this::showNoWifiDialog, 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void showNoInternetDialog() {
        handler.post(() -> {
            setStatus("Sin conexion a internet...");
            SpeedtestService.update(this, "Sin datos — esperando conexion", "");

            new AlertDialog.Builder(this)
                .setTitle("Sin Datos")
                .setMessage("No hay conexion a internet.\n\n" +
                    "Verifique su conexion y presione Aceptar para continuar.")
                .setPositiveButton("Aceptar", (d, w) -> {
                    // Validar conexión al presionar Aceptar
                    if (isConnected()) {
                        // Tiene internet — resetear flags y reintentar misma prueba
                        errorDetected.set(false);
                        setStatus("Conexion restaurada. Reintentando prueba " + currentRun + "...");
                        SpeedtestService.update(this,
                            "Conexion restaurada - reintentando prueba " + currentRun,
                            "Prueba " + currentRun + " de " + totalRuns);
                        // Resetear SIN cambiar currentRun para retomar prueba correcta
                        resultId = ""; resultUrl = ""; download = ""; upload = "";
                        ping = ""; jitter = ""; pageLoaded = false;
                        goPressed = false; pollCount = 0;
                        saved.set(false); errorDetected.set(false);
                        handler.postDelayed(() -> {
                            webView.clearCache(true);
                            webView.clearHistory();
                            webView.clearFormData();
                            android.webkit.CookieManager.getInstance()
                                .removeAllCookies(null);
                            webView.loadUrl("https://www.speedtest.net/en");
                            progressBar.setVisibility(View.VISIBLE);
                            layoutResults.setVisibility(View.GONE);
                        }, 2000);
                    } else {
                        // Sigue sin internet — volver a mostrar el mismo dialog
                        handler.postDelayed(this::showNoInternetDialog, 500);
                    }
                })
                .setCancelable(false)
                .show();
        });
    }

    private void retryRun() {
        if (currentRetry < maxRetries) {
            // Aún hay reintentos — reintentar la MISMA prueba (no cambiar currentRun)
            currentRetry++;
            int runActual = currentRun; // guardar para mostrar correcto
            setStatus("Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")...");
            SpeedtestService.update(this,
                "Reintentando prueba " + runActual +
                " (" + currentRetry + "/" + maxRetries + ")",
                "Prueba " + runActual + " de " + totalRuns);
            // Resetear estado SIN tocar currentRun
            resultId = ""; resultUrl = ""; download = ""; upload = "";
            ping = ""; jitter = ""; pageLoaded = false;
            goPressed = false; pollCount = 0;
            saved.set(false);
            errorDetected.set(false);
            // Recargar la página para reintentar la misma prueba
            handler.postDelayed(() -> {
                webView.clearCache(true);
                webView.clearHistory();
                webView.clearFormData();
                android.webkit.CookieManager cm =
                    android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(false);
                cm.removeAllCookies(null);
                cm.flush();
                webView.loadUrl("https://www.speedtest.net/en");
                progressBar.setVisibility(View.VISIBLE);
                layoutResults.setVisibility(View.GONE);
            }, 3000);
        } else {
            // Reintentos agotados — mostrar dialog al usuario
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setDatabaseEnabled(false);
        s.setSaveFormData(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setGeolocationEnabled(true);
        // User agent escritorio — necesario para prueba completa (descarga + subida)
        s.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36");
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
            public void onProgressChanged(WebView view, int progress) {
                if (progress == 100) {
                    if (phase.equals("speedtest") && !pageLoaded && !goPressed) {
                        pageLoaded = true;
                        handler.postDelayed(MainActivity.this::pressGo, 5000);
                    } else if (phase.equals("nperf") && !nPageLoaded && !nGoPressed) {
                        nPageLoaded = true;
                        handler.postDelayed(MainActivity.this::pressNperfGo, 8000);
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null) return;
                if (phase.equals("speedtest") && url.contains("/result/") && !saved.get()) {
                    handler.postDelayed(MainActivity.this::saveTxt, 4000);
                } else if (phase.equals("nperf") && url.contains("/result") && !nSaved.get()) {
                    if (nSaved.compareAndSet(false, true))
                        handler.postDelayed(MainActivity.this::extractNperfMetrics, 4000);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                AtomicBoolean errFlag = phase.equals("nperf") ? nErrorDetected : errorDetected;
                if (!saved.get() && !nSaved.get() && errFlag.compareAndSet(false, true)) {
                    handler.post(() -> {
                        if (!isWifiConnected()) {
                            showNoWifiDialog();
                        } else if (!isConnected()) {
                            showNoInternetDialog();
                        } else {
                            setStatus("Error de red - reintentando...");
                            if (phase.equals("nperf"))
                                handler.postDelayed(MainActivity.this::startNperf, 3000);
                            else
                                handler.postDelayed(MainActivity.this::retryRun, 3000);
                        }
                    });
                }
            }

            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (request.isForMainFrame() && !saved.get() &&
                            errorDetected.compareAndSet(false, true)) {
                        handler.post(() -> {
                            if (!isWifiConnected()) {
                                showNoWifiDialog();
                            } else if (!isConnected()) {
                                showNoInternetDialog();
                            } else {
                                setStatus("Error de red - reintentando...");
                                handler.postDelayed(MainActivity.this::retryRun, 3000);
                            }
                        });
                    }
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
                    java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                        .compile("result/([\\w-]+)")
                        .matcher(curUrl);
                    if (urlMatcher.find() && saved.compareAndSet(false, true)) {
                        resultId  = urlMatcher.group(1);
                        resultUrl = curUrl;
                        handler.post(() -> {
                            showPanel();
                            if (!isInBackground) {
                                extractMetricsThenSave();
                            } else {
                                handler.postDelayed(MainActivity.this::saveTxt, 1000);
                            }
                        });
                        return;
                    }
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

    private void extractMetricsThenSave() {
        if (webView == null) { saveTxt(); return; }
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
                handler.postDelayed(MainActivity.this::saveTxt, 500);
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

            if (ready && saved.compareAndSet(false, true)) {
                resultId  = rid.isEmpty()  ? resultId  : rid;
                resultUrl = url == null    ? resultUrl : url;
                download  = hasDl ? dl : download;
                upload    = hasUl ? ul : upload;
                ping = pg; jitter = jt;
                handler.post(() -> {
                    showPanel();
                    handler.postDelayed(MainActivity.this::saveTxt, 2000);
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // GUARDAR TXT — I/O en hilo separado
    // ══════════════════════════════════════════════════════════════════════
    private void saveTxt() {
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
            "  Operador     : " + (nOperator.isEmpty() ? "N/A" : nOperator) + "\n\n" +
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
    // NPERF — Iniciar prueba
    // ══════════════════════════════════════════════════════════════════════
    private void startNperf() {
        if (!isWifiConnected()) { showNoWifiDialog(); return; }
        if (!isConnected())     { showNoInternetDialog(); return; }

        nGoPressed = false; nPageLoaded = false; nPollCount = 0;
        nDownload = ""; nUpload = ""; nPing = ""; nJitter = "";
        nServer = ""; nOperator = "";
        nSaved.set(false); nErrorDetected.set(false);

        setStatus("Cargando nperf.com...");
        progressBar.setVisibility(View.VISIBLE);

        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true); // nperf necesita cookies para funcionar
        cm.removeAllCookies(null);
        cm.flush();

        // nperf requiere user agent móvil para renderizar correctamente
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Linux; Android 12; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.loadUrl("https://www.nperf.com/es/");
        // Limpiar panel para mostrar valores de nperf
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

        String curUrl = webView.getUrl();
        if (curUrl == null || !curUrl.contains("nperf.com")) {
            setStatus("Reintentando cargar nperf...");
            handler.postDelayed(this::startNperf, 3000);
            return;
        }

        nGoPressed = true;
        setStatus("Analizando banners nperf...");

        // Iniciar flujo nperf directamente
        runNperfAfterDebug();
    }

    private void runNperfAfterDebug() {
        handler.postDelayed(() -> {
            setStatus("Iniciando nperf automaticamente...");
            SpeedtestService.update(this,
                "nperf en curso - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);

            // El botón "Iniciar test" está dentro de un canvas — usar click por coordenadas
            String jsStart = "javascript:(function(){" +
                // Buscar el canvas del velocímetro
                "var canvas=document.querySelector('canvas');" +
                "if(!canvas){" +
                // Si no hay canvas, buscar SVG o elemento contenedor del velocímetro
                "  canvas=document.querySelector('svg,[class*=gauge],[class*=speedometer]," +
                "    [class*=dial],[class*=meter],[class*=needle],[class*=speed-gauge]," +
                "    [id*=gauge],[id*=speed],[id*=meter]');" +
                "}" +
                "if(canvas){" +
                // Click en el centro del canvas (donde está el botón Iniciar test)
                "  var rect=canvas.getBoundingClientRect();" +
                "  var cx=rect.left+rect.width/2;" +
                "  var cy=rect.top+rect.height/2;" +
                // Disparar eventos de mouse en el centro del canvas
                "  ['mousedown','mouseup','click'].forEach(function(evt){" +
                "    canvas.dispatchEvent(new MouseEvent(evt,{" +
                "      bubbles:true,cancelable:true," +
                "      clientX:cx,clientY:cy," +
                "      view:window" +
                "    }));" +
                "  });" +
                // También disparar touch events (para Android WebView)
                "  var touch=new Touch({identifier:1,target:canvas," +
                "    clientX:cx,clientY:cy,pageX:cx,pageY:cy," +
                "    screenX:cx,screenY:cy,radiusX:1,radiusY:1,rotationAngle:0,force:1});" +
                "  canvas.dispatchEvent(new TouchEvent('touchstart',{" +
                "    bubbles:true,cancelable:true,touches:[touch],targetTouches:[touch]," +
                "    changedTouches:[touch]}));" +
                "  canvas.dispatchEvent(new TouchEvent('touchend',{" +
                "    bubbles:true,cancelable:true,touches:[],targetTouches:[]," +
                "    changedTouches:[touch]}));" +
                "} else {" +
                // Fallback: click en el centro de la pantalla
                "  var cx=window.innerWidth/2,cy=window.innerHeight/2;" +
                "  var el=document.elementFromPoint(cx,cy);" +
                "  if(el){el.click();}" +
                "}" +
                "})()";

            webView.loadUrl(jsStart);
            setStatus("nperf en curso...");

            // Reintentar click 3 veces con delay
            handler.postDelayed(() -> webView.loadUrl(jsStart), 3000);
            handler.postDelayed(() -> webView.loadUrl(jsStart), 6000);

            startNperfPolling();
        }, 3000);
    }

    // ── Polling nperf cada 3s ─────────────────────────────────────────────
    private void startNperfPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nSaved.get()) return;
                nPollCount++;

                String statusTxt = "nperf prueba " + currentRun + "/" +
                    totalRuns + " — " + (nPollCount * 3) + "s";
                setStatus(statusTxt);
                SpeedtestService.update(MainActivity.this, statusTxt,
                    "Prueba " + currentRun + " de " + totalRuns);

                // Monitorear URL — nperf cambia URL al terminar
                String curUrl = webView.getUrl();
                if (curUrl != null && curUrl.contains("/result")) {
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
                        "  var hasError=b.indexOf('ERREUR')>-1||b.indexOf('ERROR')>-1" +
                        "    ||b.indexOf('error')>-1&&b.indexOf('test-error')>-1;" +
                        // Detectar si la prueba terminó: aparece "Reiniciar"
                        "  var done=b.indexOf('Reiniciar')>-1" +
                        "    ||b.indexOf('Restart')>-1||b.indexOf('Reinitier')>-1;" +
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

                if (!nSaved.get() && nPollCount >= MAX_POLL) {
                    // Timeout — guardar con lo que haya
                    if (nSaved.compareAndSet(false, true))
                        handler.post(() -> saveTxt());
                    return;
                }
                if (!nSaved.get()) handler.postDelayed(this, 3000);
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
            if ("true".equals(hasError) && nErrorDetected.compareAndSet(false, true)) {
                handler.post(() -> {
                    if (!isConnected()) showNoInternetDialog();
                    else {
                        setStatus("Error en nperf - reintentando en 5s...");
                        handler.postDelayed(this::startNperf, 5000);
                    }
                });
                return;
            }

            // Prueba terminada — "Reiniciar" visible O tenemos DL+UL
            boolean hasDl   = !nDownload.isEmpty() && !nDownload.equals("0");
            boolean hasUl   = !nUpload.isEmpty()   && !nUpload.equals("0");
            boolean finished = "true".equals(done) || (hasDl && hasUl);

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
            "  return JSON.stringify({" +
            "    dl:g(['.download-value','#download-value','[class*=download]'])," +
            "    ul:g(['.upload-value','#upload-value','[class*=upload]'])," +
            "    pg:g(['.latency-value','#latency-value','[class*=latency]'])," +
            "    jt:g(['.jitter-value','#jitter-value','[class*=jitter]'])," +
            "    srv:(document.querySelector('[class*=server]," +
            "      [class*=isp-name]')||{}).textContent||''," +
            "    op:(document.querySelector('[class*=operator]," +
            "      [class*=isp]')||{}).textContent||''" +
            "  });" +
            "})()",
            value -> {
                if (value != null && !value.equals("null")) {
                    try {
                        String v = value.replaceAll("^\"|\"$","");
                        String dl = key(v,"dl"), ul = key(v,"ul");
                        String pg = key(v,"pg"), jt = key(v,"jt");
                        String srv = key(v,"srv"), op = key(v,"op");
                        if (!dl.isEmpty())  nDownload  = dl;
                        if (!ul.isEmpty())  nUpload    = ul;
                        if (!pg.isEmpty())  nPing      = pg;
                        if (!jt.isEmpty())  nJitter    = jt;
                        if (!srv.isEmpty()) nServer    = srv.trim();
                        if (!op.isEmpty())  nOperator  = op.trim();
                    } catch (Exception e) { e.printStackTrace(); }
                }
                handler.postDelayed(MainActivity.this::saveTxt, 1000);
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // COMPLETAR SPEEDTEST — iniciar nperf antes de guardar
    // ══════════════════════════════════════════════════════════════════════
    private void onRunComplete(boolean success) {
        if (phase.equals("speedtest")) {
            // Speedtest terminó — ahora ejecutar nperf
            phase = "nperf";
            setStatus("Speedtest OK. Iniciando nperf...");
            SpeedtestService.update(this,
                "Iniciando nperf - prueba " + currentRun,
                "Prueba " + currentRun + " de " + totalRuns);
            handler.postDelayed(this::startNperf, 2000);
            return;
        }

        // nperf también terminó — guardar TXT combinado
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
                "Prueba " + currentRun + " OK. Siguiente en " +
                waitBetween + "s...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::startRun, waitBetween * 1000L);
        } else {
            // Todas completadas — desbloquear navegación
            isRunning = false;
            stopBannerWatcher();
            releaseWakeLock();
            SpeedtestService.stop(this);
            setStatus("COMPLETADO: " + totalRuns + " pruebas guardadas");
            Toast.makeText(this,
                "Todas las pruebas completadas (" + totalRuns + ")",
                Toast.LENGTH_LONG).show();
        }
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
            tvResultId.setText("nperf — " + (nServer.isEmpty() ? "midiendo..." : nServer));
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
