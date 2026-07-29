package com.netlife.speedtestnl;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

/**
 * Controls the nPerf consent and start screen without reloading the WebView.
 *
 * JavaScript is used only to inspect the DOM and obtain viewport coordinates.
 * The actual interaction is sent as a native Android touch event so nPerf sees
 * the same input path as a physical screen tap.
 */
final class NperfAutomation {

    interface Listener {
        void onStatus(String message);
        void onStartTouchSent();
        void onManualStartAvailable();
    }

    private static final String TAG = "SpeedtestNL-nPerf";
    private static final int MAX_START_TAPS = 4;
    private static final int MAX_ACTIVE_SCANS = 12;

    private final WebView webView;
    private final Handler handler;
    private final Listener listener;

    private int generation = 0;
    private int scanCount = 0;
    private int startTapCount = 0;
    private boolean pollingNotified = false;
    private boolean manualNoticeSent = false;

    NperfAutomation(WebView webView, Handler handler, Listener listener) {
        this.webView = webView;
        this.handler = handler;
        this.listener = listener;
    }

    void begin() {
        generation++;
        scanCount = 0;
        startTapCount = 0;
        pollingNotified = false;
        manualNoticeSent = false;
        inspect(generation, 350L);
    }

    void cancel() {
        generation++;
    }

    private boolean isActive(int token) {
        return token == generation;
    }

    private void inspect(int token, long delayMs) {
        handler.postDelayed(() -> {
            if (!isActive(token) || webView == null) return;
            scanCount++;
            webView.evaluateJavascript(buildInspectionScript(), value -> {
                if (!isActive(token)) return;

                JSONObject result = parseJavascriptObject(value);
                String state = result.optString("state", "invalid").toLowerCase(Locale.ROOT);
                float x = (float) result.optDouble("x", -1d);
                float y = (float) result.optDouble("y", -1d);
                float viewportWidth = (float) result.optDouble("vw", -1d);
                float viewportHeight = (float) result.optDouble("vh", -1d);
                String kind = result.optString("kind", "");

                Log.i(TAG, "scan=" + scanCount + " state=" + state +
                    " kind=" + kind + " css=" + x + "," + y +
                    " viewport=" + viewportWidth + "x" + viewportHeight);

                switch (state) {
                    case "consent":
                        listener.onStatus("Aceptando cookies nperf...");
                        tapCssPoint(token, x, y, viewportWidth, viewportHeight,
                            () -> inspect(token, 1200L));
                        break;

                    case "start":
                        sendStartTouch(token, x, y, viewportWidth, viewportHeight, kind);
                        break;

                    case "canvas":
                        if (startTapCount < 2) {
                            sendStartTouch(token, x, y, viewportWidth, viewportHeight, "canvas");
                        } else {
                            notifyPollingOnce();
                            listener.onStatus("nperf: esperando que el medidor responda...");
                            inspect(token, 5000L);
                        }
                        break;

                    case "none":
                    case "invalid":
                    case "error":
                    default:
                        handleNoTarget(token, state);
                        break;
                }
            });
        }, delayMs);
    }

    private void sendStartTouch(int token, float x, float y,
            float viewportWidth, float viewportHeight, String kind) {
        if (!isActive(token)) return;

        if (startTapCount >= MAX_START_TAPS) {
            notifyPollingOnce();
            showManualNoticeOnce();
            inspect(token, 7000L);
            return;
        }

        startTapCount++;
        listener.onStatus("Enviando toque a Iniciar test (" + startTapCount +
            "/" + MAX_START_TAPS + ")...");
        tapCssPoint(token, x, y, viewportWidth, viewportHeight, () -> {
            if (!isActive(token)) return;
            notifyPollingOnce();
            listener.onStatus("Toque enviado a nperf; verificando inicio...");
            inspect(token, 3200L);
        });
    }

    private void handleNoTarget(int token, String state) {
        if (!isActive(token)) return;

        // Deterministic fallbacks based on the nPerf layout. They are expressed
        // as percentages of the WebView, so they remain stable across devices.
        if (scanCount == 1) {
            listener.onStatus("Buscando consentimiento nperf...");
            tapNormalized(token, 0.50f, 0.91f, () -> inspect(token, 1300L));
            return;
        }

        if (scanCount == 2) {
            listener.onStatus("Aplicando toque de respaldo a Iniciar test...");
            startTapCount++;
            tapNormalized(token, 0.50f, 0.52f, () -> {
                notifyPollingOnce();
                inspect(token, 3200L);
            });
            return;
        }

        notifyPollingOnce();
        if (scanCount >= MAX_ACTIVE_SCANS) {
            showManualNoticeOnce();
            inspect(token, 8000L);
        } else {
            listener.onStatus("nperf aún inicializando; sin recargar la página...");
            inspect(token, 3000L);
        }

        Log.w(TAG, "No actionable target. state=" + state + " scan=" + scanCount);
    }

    private void notifyPollingOnce() {
        if (pollingNotified) return;
        pollingNotified = true;
        listener.onStartTouchSent();
    }

    private void showManualNoticeOnce() {
        if (manualNoticeSent) return;
        manualNoticeSent = true;
        listener.onManualStartAvailable();
    }

    private void tapCssPoint(int token, float cssX, float cssY,
            float viewportWidth, float viewportHeight, Runnable afterTap) {
        if (!isActive(token)) return;
        if (cssX <= 0 || cssY <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            Log.w(TAG, "Invalid CSS point; using normalized fallback");
            tapNormalized(token, 0.50f, 0.52f, afterTap);
            return;
        }

        webView.post(() -> {
            if (!isActive(token)) return;
            float x = cssX * webView.getWidth() / viewportWidth;
            float y = cssY * webView.getHeight() / viewportHeight;
            dispatchTap(token, x, y, afterTap);
        });
    }

    private void tapNormalized(int token, float normalizedX, float normalizedY,
            Runnable afterTap) {
        if (!isActive(token)) return;
        webView.post(() -> {
            if (!isActive(token)) return;
            float x = webView.getWidth() * normalizedX;
            float y = webView.getHeight() * normalizedY;
            dispatchTap(token, x, y, afterTap);
        });
    }

    private void dispatchTap(int token, float rawX, float rawY, Runnable afterTap) {
        if (!isActive(token) || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;

        float x = Math.max(1f, Math.min(webView.getWidth() - 1f, rawX));
        float y = Math.max(1f, Math.min(webView.getHeight() - 1f, rawY));
        long downTime = SystemClock.uptimeMillis();

        Log.i(TAG, "Native tap view=" + x + "," + y +
            " size=" + webView.getWidth() + "x" + webView.getHeight());

        MotionEvent down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        webView.dispatchTouchEvent(down);
        down.recycle();

        handler.postDelayed(() -> {
            if (!isActive(token)) return;
            long upTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            webView.dispatchTouchEvent(up);
            up.recycle();
            if (afterTap != null) handler.postDelayed(afterTap, 300L);
        }, 140L);
    }

    private JSONObject parseJavascriptObject(String value) {
        try {
            Object parsed = new JSONTokener(value == null ? "null" : value).nextValue();
            for (int i = 0; i < 2 && parsed instanceof String; i++) {
                parsed = new JSONTokener((String) parsed).nextValue();
            }
            if (parsed instanceof JSONObject) return (JSONObject) parsed;
        } catch (Exception error) {
            Log.e(TAG, "Unable to parse evaluateJavascript result: " + value, error);
        }
        return new JSONObject();
    }

    private String buildInspectionScript() {
        return "(function(){try{" +
            "var VW=window.innerWidth||document.documentElement.clientWidth||1;" +
            "var VH=window.innerHeight||document.documentElement.clientHeight||1;" +
            "function visible(e){if(!e)return false;var r=e.getBoundingClientRect();" +
            "var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return r.width>16&&r.height>12&&r.bottom>0&&r.right>0&&r.top<VH&&r.left<VW" +
            "&&s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity||'1')>0;}" +
            "function label(e){return (e.textContent||e.value||e.getAttribute('aria-label')||" +
            "e.getAttribute('title')||'').replace(/\\s+/g,' ').trim().toLowerCase();}" +
            "function point(e,ox,oy){var r=e.getBoundingClientRect();return {x:ox+r.left+r.width/2," +
            "y:oy+r.top+r.height/2};}" +
            "function result(state,kind,p){return {state:state,kind:kind||'',x:p?p.x:-1,y:p?p.y:-1," +
            "vw:VW,vh:VH};}" +
            "function scan(d,ox,oy){if(!d||!d.body)return null;" +
            "var body=(d.body.innerText||'').toLowerCase();" +
            "var nodes=d.querySelectorAll('button,a,[role=button],input[type=button]," +
            "input[type=submit],div,span');" +
            "var cookie=body.indexOf('política de uso de cookies')>-1||" +
            "body.indexOf('politica de uso de cookies')>-1||body.indexOf('cookie policy')>-1||" +
            "body.indexOf('uso de cookies y privacidad')>-1;" +
            "if(cookie){for(var i=0;i<nodes.length;i++){var c=nodes[i],ct=label(c);" +
            "if(visible(c)&&(ct==='ok'||ct==='aceptar'||ct==='accept'||ct==='agree'))" +
            "return result('consent','button',point(c,ox,oy));}}" +
            "var words=['iniciar test','iniciar prueba','comenzar test','start test','lancer le test'];" +
            "var best=null,bestArea=1e20;" +
            "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n))continue;var t=label(n);" +
            "if(t.length>70)continue;var match=false;for(var w=0;w<words.length;w++){" +
            "if(t===words[w]||t.indexOf(words[w])>-1){match=true;break;}}" +
            "if(match){var nr=n.getBoundingClientRect(),area=nr.width*nr.height;" +
            "if(area>0&&area<bestArea){best=n;bestArea=area;}}}" +
            "if(best)return result('start','button',point(best,ox,oy));" +
            "var canvases=d.querySelectorAll('canvas'),canvas=null,canvasArea=0;" +
            "for(var k=0;k<canvases.length;k++){var cv=canvases[k],cr=cv.getBoundingClientRect();" +
            "var ca=cr.width*cr.height;if(visible(cv)&&cr.width>130&&cr.height>130&&ca>canvasArea){" +
            "canvas=cv;canvasArea=ca;}}" +
            "if(canvas)return result('canvas','canvas',point(canvas,ox,oy));" +
            "return null;}" +
            "var r=scan(document,0,0);if(r)return JSON.stringify(r);" +
            "var fs=document.querySelectorAll('iframe');for(var f=0;f<fs.length;f++){try{" +
            "var fr=fs[f].getBoundingClientRect();r=scan(fs[f].contentDocument,fr.left,fr.top);" +
            "if(r)return JSON.stringify(r);}catch(ignore){}}" +
            "return JSON.stringify(result('none','',null));" +
            "}catch(e){return JSON.stringify({state:'error',message:String(e)," +
            "vw:window.innerWidth||1,vh:window.innerHeight||1});}})()";
    }
}
