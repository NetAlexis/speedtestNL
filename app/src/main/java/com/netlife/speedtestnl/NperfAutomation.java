package com.netlife.speedtestnl;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

/**
 * Controls nPerf consent and start activation without reloading the WebView.
 *
 * DOM inspection only discovers state and coordinates. User activation is sent
 * through native Android key/touch events, which is more reliable for canvas
 * based controls than synthetic JavaScript events.
 */
final class NperfAutomation {

    interface Listener {
        void onStatus(String message);
        void onStartTouchSent();
        void onManualStartAvailable();
        void onEngineError(String message);
    }

    private static final String TAG = "SpeedtestNL-nPerf";
    private static final int MAX_START_ACTIVATIONS = 5;
    private static final int MAX_ACTIVE_SCANS = 16;

    private final WebView webView;
    private final Handler handler;
    private final Listener listener;

    private int generation = 0;
    private int scanCount = 0;
    private int startActivationCount = 0;
    private int visualFallbackIndex = 0;
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
        startActivationCount = 0;
        visualFallbackIndex = 0;
        pollingNotified = false;
        manualNoticeSent = false;
        inspect(generation, 250L);
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
                String state = result.optString("state", "invalid")
                    .toLowerCase(Locale.ROOT);
                String message = result.optString("message", "");
                String kind = result.optString("kind", "");
                float x = (float) result.optDouble("x", -1d);
                float y = (float) result.optDouble("y", -1d);
                float viewportWidth = (float) result.optDouble("vw", -1d);
                float viewportHeight = (float) result.optDouble("vh", -1d);

                Log.i(TAG, "scan=" + scanCount + " state=" + state +
                    " kind=" + kind + " css=" + x + "," + y +
                    " viewport=" + viewportWidth + "x" + viewportHeight +
                    (message.isEmpty() ? "" : " message=" + message));

                switch (state) {
                    case "engine_error":
                        generation++;
                        listener.onEngineError(message.isEmpty()
                            ? "nPerf no pudo inicializar el motor" : message);
                        break;

                    case "consent":
                        listener.onStatus("Aceptando cookies nperf...");
                        activateTarget(token, x, y, viewportWidth, viewportHeight,
                            true, true, () -> inspect(token, 1100L));
                        break;

                    case "start":
                        sendStartActivation(token, x, y, viewportWidth,
                            viewportHeight, kind, true);
                        break;

                    case "canvas":
                        sendStartActivation(token, x, y, viewportWidth,
                            viewportHeight, "canvas", false);
                        break;

                    case "initializing":
                        listener.onStatus("nperf inicializando motor y servidor...");
                        inspect(token, 1300L);
                        break;

                    case "running":
                        notifyPollingOnce();
                        listener.onStatus("nperf iniciado. Esperando resultados...");
                        inspect(token, 5000L);
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

    private void sendStartActivation(int token, float x, float y,
            float viewportWidth, float viewportHeight, String kind,
            boolean keyboardFirst) {
        if (!isActive(token)) return;

        if (startActivationCount >= MAX_START_ACTIVATIONS) {
            generation++;
            listener.onEngineError("nPerf no respondió a las activaciones de Iniciar test");
            return;
        }

        startActivationCount++;
        listener.onStatus("Activando Iniciar test (" + startActivationCount +
            "/" + MAX_START_ACTIVATIONS + ", " + kind + ")...");

        activateTarget(token, x, y, viewportWidth, viewportHeight,
            keyboardFirst, true, () -> {
                if (!isActive(token)) return;
                notifyPollingOnce();
                listener.onStatus("Activación enviada a nperf; verificando motor...");
                inspect(token, 2600L);
            });
    }

    private void activateTarget(int token, float x, float y,
            float viewportWidth, float viewportHeight, boolean keyboardFirst,
            boolean sendTouch, Runnable after) {
        if (!isActive(token)) return;

        Runnable touchOrFinish = () -> {
            if (!isActive(token)) return;
            if (sendTouch) {
                tapCssPoint(token, x, y, viewportWidth, viewportHeight, after);
            } else if (after != null) {
                handler.postDelayed(after, 250L);
            }
        };

        if (keyboardFirst) {
            dispatchEnter(token, () -> handler.postDelayed(touchOrFinish, 220L));
        } else {
            touchOrFinish.run();
        }
    }

    private void handleNoTarget(int token, String state) {
        if (!isActive(token)) return;

        // First fallback targets the consent button shown at the bottom.
        if (scanCount == 1) {
            listener.onStatus("Buscando consentimiento nperf...");
            tapNormalized(token, 0.50f, 0.92f, () -> inspect(token, 1100L));
            return;
        }

        // Canvas/layout fallback: sweep only the vertical area where the dark
        // circular start control is rendered on desktop nPerf.
        final float[] startY = {0.31f, 0.39f, 0.47f};
        if (visualFallbackIndex < startY.length &&
                startActivationCount < MAX_START_ACTIVATIONS) {
            float y = startY[visualFallbackIndex++];
            startActivationCount++;
            listener.onStatus("Toque visual de respaldo nperf (" +
                visualFallbackIndex + "/" + startY.length + ")...");
            tapNormalized(token, 0.50f, y, () -> {
                notifyPollingOnce();
                inspect(token, 2600L);
            });
            return;
        }

        notifyPollingOnce();
        if (scanCount >= MAX_ACTIVE_SCANS) {
            generation++;
            listener.onEngineError("nPerf no presentó un control de inicio operativo");
        } else {
            listener.onStatus("nperf aún preparando el motor; sin recargar...");
            inspect(token, 2500L);
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

    private void dispatchEnter(int token, Runnable after) {
        if (!isActive(token)) return;
        webView.post(() -> {
            if (!isActive(token)) return;
            webView.requestFocus(View.FOCUS_DOWN);
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_ENTER, 0);
            KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0);
            webView.dispatchKeyEvent(down);
            webView.dispatchKeyEvent(up);
            Log.i(TAG, "Native ENTER dispatched");
            if (after != null) handler.postDelayed(after, 180L);
        });
    }

    private void tapCssPoint(int token, float cssX, float cssY,
            float viewportWidth, float viewportHeight, Runnable afterTap) {
        if (!isActive(token)) return;
        if (cssX <= 0 || cssY <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            Log.w(TAG, "Invalid CSS point; using visual fallback");
            tapNormalized(token, 0.50f, 0.39f, afterTap);
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
            if (afterTap != null) handler.postDelayed(afterTap, 280L);
        }, 110L);
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
            "var vv=window.visualViewport;" +
            "var VW=(vv&&vv.width)||window.innerWidth||document.documentElement.clientWidth||1;" +
            "var VH=(vv&&vv.height)||window.innerHeight||document.documentElement.clientHeight||1;" +
            "function visible(e){if(!e)return false;var r=e.getBoundingClientRect();" +
            "var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return r.width>16&&r.height>12&&r.bottom>0&&r.right>0&&r.top<VH&&r.left<VW" +
            "&&s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity||'1')>0;}" +
            "function label(e){return (e.textContent||e.value||e.getAttribute('aria-label')||" +
            "e.getAttribute('title')||'').replace(/\\s+/g,' ').trim().toLowerCase();}" +
            "function point(e,ox,oy){var r=e.getBoundingClientRect();return {x:ox+r.left+r.width/2," +
            "y:oy+r.top+r.height/2};}" +
            "function result(state,kind,p,message){return {state:state,kind:kind||''," +
            "x:p?p.x:-1,y:p?p.y:-1,vw:VW,vh:VH,message:message||''};}" +
            "function scan(d,ox,oy){if(!d||!d.body)return null;" +
            "var body=(d.body.innerText||'').toLowerCase();" +
            "var failures=['no fue posible inicializar','no se pudo inicializar'," +
            "'error al inicializar','unable to initialize','could not initialize'," +
            "'initialization failed','impossible d inicialiser'];" +
            "for(var f=0;f<failures.length;f++){if(body.indexOf(failures[f])>-1)" +
            "return result('engine_error','',null,failures[f]);}" +
            "var nodes=d.querySelectorAll('button,a,[role=button],input[type=button]," +
            "input[type=submit],div,span');" +
            "var cookie=body.indexOf('política de uso de cookies')>-1||" +
            "body.indexOf('politica de uso de cookies')>-1||body.indexOf('cookie policy')>-1||" +
            "body.indexOf('uso de cookies y privacidad')>-1;" +
            "if(cookie){for(var i=0;i<nodes.length;i++){var c=nodes[i],ct=label(c);" +
            "if(visible(c)&&(ct==='ok'||ct==='aceptar'||ct==='accept'||ct==='agree')){" +
            "try{c.focus();}catch(ignore){}return result('consent','button',point(c,ox,oy));}}}" +
            "var words=['iniciar test','iniciar prueba','comenzar test','start test','lancer le test'];" +
            "var best=null,bestArea=1e20;" +
            "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n))continue;var t=label(n);" +
            "if(t.length>70)continue;var match=false;for(var w=0;w<words.length;w++){" +
            "if(t===words[w]||t.indexOf(words[w])>-1){match=true;break;}}" +
            "if(match){var nr=n.getBoundingClientRect(),area=nr.width*nr.height;" +
            "if(area>0&&area<bestArea){best=n;bestArea=area;}}}" +
            "if(best){try{best.setAttribute('tabindex','0');best.focus();}catch(ignore){}" +
            "return result('start','button',point(best,ox,oy));}" +
            "if(body.indexOf('inicializando')>-1||body.indexOf('initializing')>-1)" +
            "return result('initializing','',null);" +
            "var canvases=d.querySelectorAll('canvas'),canvas=null,canvasArea=0;" +
            "for(var k=0;k<canvases.length;k++){var cv=canvases[k],cr=cv.getBoundingClientRect();" +
            "var ca=cr.width*cr.height;if(visible(cv)&&cr.width>130&&cr.height>130&&ca>canvasArea){" +
            "canvas=cv;canvasArea=ca;}}" +
            "if(canvas)return result('canvas','canvas',point(canvas,ox,oy));" +
            "return null;}" +
            "var r=scan(document,0,0);if(r)return JSON.stringify(r);" +
            "var fs=document.querySelectorAll('iframe');for(var q=0;q<fs.length;q++){try{" +
            "var fr=fs[q].getBoundingClientRect();r=scan(fs[q].contentDocument,fr.left,fr.top);" +
            "if(r)return JSON.stringify(r);}catch(ignore){" +
            "if(visible(fs[q]))return JSON.stringify(result('canvas','iframe'," +
            "point(fs[q],0,0)));}}" +
            "return JSON.stringify(result('none','',null));" +
            "}catch(e){return JSON.stringify({state:'error',message:String(e)," +
            "vw:window.innerWidth||1,vh:window.innerHeight||1});}})()";
    }
}
