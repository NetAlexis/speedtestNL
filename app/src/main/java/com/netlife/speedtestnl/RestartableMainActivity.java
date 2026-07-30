package com.netlife.speedtestnl;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Launcher wrapper that exposes a clean "Volver a ejecutar" action only after
 * the configured batch has completed.
 */
public class RestartableMainActivity extends MainActivity {

    private final Handler completionHandler = new Handler(Looper.getMainLooper());
    private Button rerunButton;
    private boolean restarting = false;

    private final Runnable completionWatcher = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            TextView status = findViewById(R.id.tvStatus);
            String value = status == null || status.getText() == null
                ? "" : status.getText().toString().trim();
            boolean completed = value.startsWith("COMPLETADO:") ||
                value.startsWith("COMPLETADO con");
            if (rerunButton != null) {
                rerunButton.setVisibility(completed ? View.VISIBLE : View.GONE);
                rerunButton.setEnabled(completed && !restarting);
            }
            completionHandler.postDelayed(this, completed ? 1000L : 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installRerunButton();
        completionHandler.post(completionWatcher);
    }

    @Override
    protected void onDestroy() {
        completionHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void installRerunButton() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup contentGroup = (ViewGroup) content;
        if (contentGroup.getChildCount() == 0 ||
                !(contentGroup.getChildAt(0) instanceof LinearLayout)) {
            return;
        }

        LinearLayout root = (LinearLayout) contentGroup.getChildAt(0);
        rerunButton = new Button(this);
        rerunButton.setText("VOLVER A EJECUTAR");
        rerunButton.setTextColor(Color.WHITE);
        rerunButton.setTextSize(15f);
        rerunButton.setAllCaps(false);
        rerunButton.setBackgroundColor(Color.rgb(255, 140, 0));
        int horizontal = dp(18);
        int vertical = dp(12);
        rerunButton.setPadding(horizontal, vertical, horizontal, vertical);
        rerunButton.setVisibility(View.GONE);
        rerunButton.setOnClickListener(view -> restartFromZero());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(12), dp(8), dp(12), dp(10));

        // Insert before the weighted WebView so the button remains visible.
        int index = Math.max(0, root.getChildCount() - 1);
        root.addView(rerunButton, index, params);
    }

    private void restartFromZero() {
        if (restarting) return;
        restarting = true;
        if (rerunButton != null) {
            rerunButton.setEnabled(false);
            rerunButton.setText("Reiniciando...");
        }

        NperfBrowserCoordinator.cancel(this, null);
        SpeedtestService.stop(this);

        Intent restart = new Intent(this, RestartableMainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
            Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
