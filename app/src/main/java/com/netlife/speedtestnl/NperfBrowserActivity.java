package com.netlife.speedtestnl;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.List;

/**
 * Owns the nPerf browser session. MainActivity receives a result only after the
 * accessibility controller has extracted a complete download/upload result.
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private ProgressBar progressBar;
    private String token = "";
    private boolean receiverRegistered = false;
    private boolean browserLaunched = false;
    private boolean setupDialogVisible = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
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
                Intent result = copyResultExtras(intent);
                NperfBrowserActivity.this.setResult(Activity.RESULT_OK, result);
                finish();
                return;
            }

            if (NperfBrowserCoordinator.ACTION_ERROR.equals(action)) {
                Intent result = new Intent()
                    .putExtra(NperfBrowserCoordinator.EXTRA_STATE,
                        intent.getStringExtra(NperfBrowserCoordinator.EXTRA_STATE))
                    .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                        intent.getStringExtra(NperfBrowserCoordinator.EXTRA_DETAIL));
                NperfBrowserActivity.this.setResult(Activity.RESULT_CANCELED, result);
                finish();
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
        if (isAccessibilityServiceEnabled()) {
            setupDialogVisible = false;
            if (!browserLaunched) {
                beginAndLaunch();
            } else if (NperfBrowserCoordinator.isActiveToken(this, token)) {
                // onResume after launch means the user/browser closed the tab.
                // Wait briefly so a result broadcast can win the race.
                handler.postDelayed(() -> {
                    if (!isFinishing() &&
                            NperfBrowserCoordinator.isActiveToken(this, token)) {
                        NperfBrowserCoordinator.fail(this, token,
                            "TAB_CLOSED",
                            "La pestaña de nPerf se cerró antes de completar la prueba");
                    }
                }, 1400L);
            }
        } else if (!setupDialogVisible) {
            showAccessibilitySetup();
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!token.isEmpty()) NperfBrowserCoordinator.cancel(this, token);
        setResult(Activity.RESULT_CANCELED, new Intent()
            .putExtra(NperfBrowserCoordinator.EXTRA_STATE, "USER_CANCELLED")
            .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                "El usuario canceló la prueba nPerf"));
        super.onBackPressed();
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
            .setNegativeButton("Cancelar", (dialog, which) -> {
                setResult(Activity.RESULT_CANCELED, new Intent()
                    .putExtra(NperfBrowserCoordinator.EXTRA_STATE,
                        "ACCESSIBILITY_DISABLED")
                    .putExtra(NperfBrowserCoordinator.EXTRA_DETAIL,
                        "El servicio de automatización nPerf no está habilitado"));
                finish();
            })
            .setCancelable(false)
            .show();
    }

    private void beginAndLaunch() {
        int run = getIntent().getIntExtra(EXTRA_RUN, 1);
        int total = getIntent().getIntExtra(EXTRA_TOTAL, 1);
        token = NperfBrowserCoordinator.begin(this, run, total);
        browserLaunched = true;
        progressBar.setVisibility(View.VISIBLE);

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

        setStatus("Abriendo nPerf en " +
            (browserPackage == null ? "el navegador predeterminado" : browserPackage) + "...");
        try {
            customTabsIntent.launchUrl(this, NPERF_URI);
        } catch (Exception error) {
            NperfBrowserCoordinator.fail(this, token,
                "BROWSER_UNAVAILABLE",
                "No se pudo abrir un navegador compatible: " +
                    (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()));
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
            .putExtra(NperfBrowserCoordinator.EXTRA_JITTER,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_JITTER))
            .putExtra(NperfBrowserCoordinator.EXTRA_SERVER,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_SERVER))
            .putExtra(NperfBrowserCoordinator.EXTRA_OPERATOR,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_OPERATOR))
            .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_ID,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_RESULT_ID))
            .putExtra(NperfBrowserCoordinator.EXTRA_RESULT_URL,
                source.getStringExtra(NperfBrowserCoordinator.EXTRA_RESULT_URL));
    }

    private void setStatus(String message) {
        if (statusView != null) statusView.setText(
            message == null || message.trim().isEmpty()
                ? "nPerf procesando..." : message.trim());
    }
}
