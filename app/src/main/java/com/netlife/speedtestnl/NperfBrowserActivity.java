package com.netlife.speedtestnl;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Owns one nPerf browser session and guarantees delivery of the result even if
 * the completion broadcast is missed while Chrome returns to the app.
 */
public class NperfBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_RUN = "run";
    public static final String EXTRA_TOTAL = "total";

    private static final Uri NPERF_URI = Uri.parse("https://www.nperf.com/es/");
    private static final List<String> BROWSER_PREFERENCE = Arrays.asList(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "org.mozilla.firefox"
    );

    private static final String PREFS = "nperf_browser_session";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_STATE = "state";
    private static final String KEY_RESULT_JSON = "result_json";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private ProgressBar progressBar;
    private String token = "";
    private boolean receiverRegistered = false;
    private boolean browserLaunched = false;
    private boolean setupDialogVisible = false;
    private boolean terminalDelivered = false;

    private final Runnable resultRecovery = new Runnable() {
        @Override
        public void run() {
            if (terminalDelivered || isFinishing()) return;
            if (recoverPersistedResult()) return;
            if (browserLaunched) handler.postDelayed(this, 500L);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || terminalDelivered) return;
            String incomingToken = intent.getStringExtra(
                NperfBrowserCoordinator.EXTRA_TOKEN);
            if (token.isEmpty() || !token.equals(incomingToken)) return;

            String action = intent.getAction();
            if (NperfBrowserCoordinator.ACTION_STATUS.equals(action)) {
                String detail = intent.getStringExtra(
                    NperfBrowserCoordinator.EXTRA_DETAIL);
                setStatus(detail == null ? "nPerf procesando..." : detail);
                return;
            }

            if (NperfBrowserCoordinator.ACTION_RESULT.equals(action)) {
                deliverSuccess(copyResultExtras(intent));
                return;
            }

            if (NperfBrowserCoordinator.ACTION_ERROR.equals(action)) {
                Intent result = new Intent()
                    .putExtra(NperfBrowserCoordinator.EXTRA_STATE,
                        intent.getStringExtra(NperfBrowserCoordinator.EXTRA_STATE))
                    .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                        intent.getStringExtra(NperfBrowserCoordinator.EXTRA_DETAIL));
                deliverFailure(result);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        registerCoordinatorReceiver();
        setStatus("Preparando navegador completo para nPerf...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (terminalDelivered) return;
        if (isAccessibilityServiceEnabled()) {
            setupDialogVisible = false;
            if (!browserLaunched) {
                beginAndLaunch();
            } else {
                if (recoverPersistedResult()) return;
                if (NperfBrowserCoordinator.isActiveToken(this, token)) {
                    handler.postDelayed(() -> {
                        if (terminalDelivered || isFinishing() || recoverPersistedResult()) {
                            return;
                        }
                        if (NperfBrowserCoordinator.isActiveToken(this, token)) {
                            NperfBrowserCoordinator.fail(this, token,
                                "TAB_CLOSED",
                                "La pestaña de nPerf se cerró antes de completar la prueba");
                        }
                    }, 1800L);
                } else {
                    handler.postDelayed(() -> {
                        if (!terminalDelivered && !isFinishing() &&
                                !recoverPersistedResult()) {
                            deliverFailure(new Intent()
                                .putExtra(NperfBrowserCoordinator.EXTRA_STATE,
                                    "SESSION_RESULT_MISSING")
                                .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                                    "nPerf terminó, pero no se encontró un resultado recuperable"));
                        }
                    }, 1800L);
                }
            }
        } else if (!setupDialogVisible) {
            showAccessibilitySetup();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!token.isEmpty()) NperfBrowserCoordinator.cancel(this, token);
        deliverFailure(new Intent()
            .putExtra(NperfBrowserCoordinator.EXTRA_STATE, "USER_CANCELLED")
            .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                "El usuario canceló la prueba nPerf"));
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(16, 17, 24));

        TextView title = new TextView(this);
        title.setText("nPerf · navegador completo");
        title.setTextColor(Color.rgb(255, 152, 0));
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 32, 0, 24);
        root.addView(statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        root.addView(progressBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText(
            "Speedtest ya terminó. Esta pantalla conservará sus métricas " +
            "mientras nPerf se ejecuta en una pestaña segura del navegador.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(14f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 32, 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void showAccessibilitySetup() {
        setupDialogVisible = true;
        progressBar.setVisibility(View.GONE);
        setStatus("Se requiere habilitar una vez la automatización nPerf.");

        new AlertDialog.Builder(this)
            .setTitle("Activar automatización nPerf")
            .setMessage(
                "Android exige que el usuario habilite manualmente el servicio " +
                "de accesibilidad. El servicio solo actúa durante una prueba " +
                "nPerf iniciada por Speedtest NL y solo sobre el navegador elegido.\n\n" +
                "En la siguiente pantalla seleccione ‘Automatización nPerf de " +
                "Speedtest NL’ y active Permitir.")
            .setPositiveButton("Abrir configuración", (dialog, which) -> {
                Intent settingsIntent = new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(settingsIntent);
            })
            .setNegativeButton("Cancelar", (dialog, which) -> deliverFailure(
                new Intent()
                    .putExtra(NperfBrowserCoordinator.EXTRA_STATE,
                        "ACCESSIBILITY_DISABLED")
                    .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                        "El servicio de automatización nPerf no está habilitado")))
            .setCancelable(false)
            .show();
    }

    private void beginAndLaunch() {
        int run = getIntent().getIntExtra(EXTRA_RUN, 1);
        int total = getIntent().getIntExtra(EXTRA_TOTAL, 1);
        token = NperfBrowserCoordinator.begin(this, run, total);
        browserLaunched = true;
        progressBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(resultRecovery);
        handler.postDelayed(resultRecovery, 500L);

        String browserPackage = CustomTabsClient.getPackageName(
            this, BROWSER_PREFERENCE, false);
        NperfBrowserCoordinator.setBrowserPackage(this, browserPackage);

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setUrlBarHidingEnabled(false)
            .build();
        if (browserPackage != null && !browserPackage.isEmpty()) {
            customTabsIntent.intent.setPackage(browserPackage);
        }
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        Uri sessionUri = NPERF_URI.buildUpon()
            .appendQueryParameter("stnl", token)
            .build();

        setStatus("Abriendo nPerf en " +
            (browserPackage == null ? "el navegador predeterminado" : browserPackage) + "...");
        try {
            customTabsIntent.launchUrl(this, sessionUri);
        } catch (Exception error) {
            NperfBrowserCoordinator.fail(this, token,
                "BROWSER_UNAVAILABLE",
                "No se pudo abrir un navegador compatible: " +
                    (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private boolean recoverPersistedResult() {
        if (terminalDelivered || token.isEmpty()) return false;
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!token.equals(preferences.getString(KEY_TOKEN, ""))) return false;
        String state = preferences.getString(KEY_STATE, "");
        String json = preferences.getString(KEY_RESULT_JSON, "");
        if (!"COMPLETE".equals(state) || json == null || json.trim().isEmpty()) {
            return false;
        }

        try {
            JSONObject object = new JSONObject(json);
            Intent result = new Intent()
                .putExtra(NperfBrowserCoordinator.EXTRA_DOWNLOAD,
                    object.optString(NperfBrowserCoordinator.EXTRA_DOWNLOAD, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_UPLOAD,
                    object.optString(NperfBrowserCoordinator.EXTRA_UPLOAD, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_LATENCY,
                    object.optString(NperfBrowserCoordinator.EXTRA_LATENCY, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_JITTER, "")
                .putExtra(NperfBrowserCoordinator.EXTRA_SERVER,
                    object.optString(NperfBrowserCoordinator.EXTRA_SERVER, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_OPERATOR,
                    object.optString(NperfBrowserCoordinator.EXTRA_OPERATOR, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_ID,
                    object.optString(NperfBrowserCoordinator.EXTRA_RESULT_ID, ""))
                .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_URL,
                    object.optString(NperfBrowserCoordinator.EXTRA_RESULT_URL, ""));
            deliverSuccess(result);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String expected = new ComponentName(this,
            NperfBrowserAutomationService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.trim().isEmpty()) return false;
        for (String component : enabled.split(":")) {
            if (expected.equalsIgnoreCase(component)) return true;
        }
        return false;
    }

    private void registerCoordinatorReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(NperfBrowserCoordinator.ACTION_STATUS);
        filter.addAction(NperfBrowserCoordinator.ACTION_RESULT);
        filter.addAction(NperfBrowserCoordinator.ACTION_ERROR);
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    private Intent copyResultExtras(Intent source) {
        return new Intent()
            .putExtra(NperfBrowserCoordinator.EXTRA_DOWNLOAD,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_DOWNLOAD))
            .putExtra(NperfBrowserCoordinator.EXTRA_UPLOAD,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_UPLOAD))
            .putExtra(NperfBrowserCoordinator.EXTRA_LATENCY,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_LATENCY))
            .putExtra(NperfBrowserCoordinator.EXTRA_JITTER, "")
            .putExtra(NperfBrowserCoordinator.EXTRA_SERVER,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_SERVER))
            .putExtra(NperfBrowserCoordinator.EXTRA_OPERATOR,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_OPERATOR))
            .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_ID,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_RESULT_ID))
            .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_URL,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_RESULT_URL));
    }

    private void deliverSuccess(Intent result) {
        if (terminalDelivered) return;
        terminalDelivered = true;
        handler.removeCallbacksAndMessages(null);
        setResult(Activity.RESULT_OK, result == null ? new Intent() : result);
        finish();
    }

    private void deliverFailure(Intent result) {
        if (terminalDelivered) return;
        terminalDelivered = true;
        handler.removeCallbacksAndMessages(null);
        setResult(Activity.RESULT_CANCELED, result == null ? new Intent() : result);
        finish();
    }

    private void setStatus(String message) {
        if (statusView != null) statusView.setText(
            message == null || message.trim().isEmpty()
                ? "nPerf procesando..." : message.trim());
    }
}
