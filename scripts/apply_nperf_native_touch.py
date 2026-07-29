#!/usr/bin/env python3
from pathlib import Path
import re

path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "    private final AtomicBoolean finalSaveStarted = new AtomicBoolean(false);\n"
    "    private int nperfRetry = 0;",
    "    private final AtomicBoolean finalSaveStarted = new AtomicBoolean(false);\n"
    "    private final AtomicBoolean nperfPollingStarted = new AtomicBoolean(false);\n"
    "    private int nperfRetry = 0;",
    "nPerf polling guard field",
)

replace_once(
    "        nSaved.set(false);\n"
    "        nErrorDetected.set(false);\n\n"
    "        nperfRetry = 0;",
    "        nSaved.set(false);\n"
    "        nErrorDetected.set(false);\n"
    "        nperfPollingStarted.set(false);\n\n"
    "        nperfRetry = 0;",
    "reset nPerf polling guard",
)

replace_once(
    "        clearWebViewSession(true);\n"
    "        setNperfUserAgent();\n"
    "        webView.loadUrl(NPERF_URL);",
    "        prepareNperfSession();\n"
    "        setNperfUserAgent();\n"
    "        webView.loadUrl(NPERF_URL);",
    "preserve nPerf cookies",
)

replace_once(
    "        cm.flush();\n"
    "    }\n\n"
    "    private void reloadSpeedtestCurrentAttempt()",
    """        cm.flush();
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

    private void reloadSpeedtestCurrentAttempt()""",
    "nPerf cookie session helper",
)

start_pattern = re.compile(
    r"    private void runNperfAfterDebug\(\) \{.*?\n// ── Polling nperf cada 3s",
    re.DOTALL,
)
start_replacement = r'''    private void runNperfAfterDebug() {
        dismissNperfConsentThenStart(1);
    }

    private void dismissNperfConsentThenStart(int attempt) {
        if (!"nperf".equals(phase) || nSaved.get() || webView == null) return;
        setStatus("Revisando consentimiento nperf...");

        String jsConsent = "(function(){try{" +
            "function visible(e){if(!e)return false;var r=e.getBoundingClientRect();" +
            "var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return r.width>20&&r.height>15&&s.display!=='none'&&s.visibility!=='hidden';}" +
            "function point(e,ox,oy){var r=e.getBoundingClientRect();" +
            "return {x:ox+r.left+r.width/2,y:oy+r.top+r.height/2};}" +
            "function scan(d,ox,oy){if(!d||!d.body)return null;" +
            "var body=(d.body.innerText||'').toLowerCase();" +
            "var cookie=body.indexOf('política de uso de cookies')>-1||" +
            "body.indexOf('politica de uso de cookies')>-1||" +
            "body.indexOf('cookie policy')>-1;" +
            "if(!cookie)return null;" +
            "var ns=d.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit]');" +
            "for(var i=0;i<ns.length;i++){var n=ns[i];" +
            "var t=(n.textContent||n.value||n.getAttribute('aria-label')||'').trim().toLowerCase();" +
            "if(visible(n)&&(t==='ok'||t==='aceptar'||t==='accept'||t==='agree')){" +
            "var p=point(n,ox,oy);try{n.click();}catch(x){}" +
            "return {state:'consent',x:p.x,y:p.y};}}return null;}" +
            "var r=scan(document,0,0);if(r)return JSON.stringify(r);" +
            "var fs=document.querySelectorAll('iframe');" +
            "for(var f=0;f<fs.length;f++){try{var fr=fs[f].getBoundingClientRect();" +
            "r=scan(fs[f].contentDocument,fr.left,fr.top);if(r)return JSON.stringify(r);}catch(x){}}" +
            "return JSON.stringify({state:'none'});" +
            "}catch(e){return JSON.stringify({state:'error'});}})()";

        webView.evaluateJavascript(jsConsent, value -> {
            if (!"nperf".equals(phase) || nSaved.get()) return;
            String json = decodeJsResult(value);
            if ("consent".equals(key(json, "state"))) {
                float x = parseFloatSafe(key(json, "x"));
                float y = parseFloatSafe(key(json, "y"));
                setStatus("Aceptando cookies nperf...");
                tapWebViewCssPoint(x, y,
                    () -> handler.postDelayed(() -> attemptNperfStart(attempt), 1600));
            } else {
                attemptNperfStart(attempt);
            }
        });
    }

    private void attemptNperfStart(int attempt) {
        if (!"nperf".equals(phase) || nSaved.get() || webView == null) return;
        setStatus("Buscando inicio nperf (" + attempt + "/8)...");

        String jsStart = "(function(){try{" +
            "function visible(e){if(!e)return false;var r=e.getBoundingClientRect();" +
            "var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return r.width>20&&r.height>20&&s.display!=='none'&&s.visibility!=='hidden';}" +
            "function point(e,ox,oy){var r=e.getBoundingClientRect();" +
            "return {x:ox+r.left+r.width/2,y:oy+r.top+r.height/2};}" +
            "function scan(d,ox,oy){if(!d)return null;" +
            "var nodes=d.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span');" +
            "var words=['iniciar test','iniciar prueba','comenzar test','start test','lancer le test'];" +
            "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!visible(n))continue;" +
            "var t=(n.textContent||n.value||n.getAttribute('aria-label')||'').trim().toLowerCase();" +
            "for(var w=0;w<words.length;w++){if(t===words[w]||t.indexOf(words[w])>-1){" +
            "var p=point(n,ox,oy);return {state:'target',kind:'button',x:p.x,y:p.y};}}}" +
            "var cs=d.querySelectorAll('canvas');var best=null,bestArea=0;" +
            "for(var c=0;c<cs.length;c++){var cv=cs[c],cr=cv.getBoundingClientRect();" +
            "var area=cr.width*cr.height;if(visible(cv)&&cr.width>140&&cr.height>140&&area>bestArea){" +
            "best=cv;bestArea=area;}}" +
            "if(best){var p=point(best,ox,oy);return {state:'target',kind:'canvas',x:p.x,y:p.y};}" +
            "return null;}" +
            "var r=scan(document,0,0);if(r)return JSON.stringify(r);" +
            "var fs=document.querySelectorAll('iframe');" +
            "for(var f=0;f<fs.length;f++){try{var fr=fs[f].getBoundingClientRect();" +
            "r=scan(fs[f].contentDocument,fr.left,fr.top);if(r)return JSON.stringify(r);}catch(x){}}" +
            "var body=(document.body?document.body.innerText:'').toLowerCase();" +
            "if(body.indexOf('política de uso de cookies')>-1||body.indexOf('politica de uso de cookies')>-1)" +
            "return JSON.stringify({state:'consent'});" +
            "return JSON.stringify({state:'none'});" +
            "}catch(e){return JSON.stringify({state:'error'});}})()";

        webView.evaluateJavascript(jsStart, value -> {
            if (!"nperf".equals(phase) || nSaved.get()) return;
            String json = decodeJsResult(value);
            String state = key(json, "state");
            if ("consent".equals(state)) {
                dismissNperfConsentThenStart(attempt);
                return;
            }
            if ("target".equals(state)) {
                float x = parseFloatSafe(key(json, "x"));
                float y = parseFloatSafe(key(json, "y"));
                String kind = key(json, "kind");
                setStatus("Toque Android sobre nperf (" + kind + ")...");
                tapWebViewCssPoint(x, y, () -> {
                    setStatus("nperf iniciado. Esperando resultados...");
                    SpeedtestService.update(MainActivity.this,
                        "nperf en curso - prueba " + currentRun,
                        "Prueba " + currentRun + " de " + totalRuns);
                    startNperfPolling();
                    scheduleNperfStartRetap(x, y, 1);
                });
            } else if (attempt < 8) {
                setStatus("nperf aún inicializando. Nuevo intento...");
                handler.postDelayed(() -> dismissNperfConsentThenStart(attempt + 1), 2500);
            } else {
                nGoPressed = false;
                setStatus("No se encontró el inicio de nperf. Reintentando carga...");
                handler.postDelayed(this::retryNperf, 2000);
            }
        });
    }

    private void scheduleNperfStartRetap(float cssX, float cssY, int retap) {
        handler.postDelayed(() -> {
            if (!"nperf".equals(phase) || nSaved.get()) return;
            boolean hasMetrics = !nDownload.isEmpty() || !nUpload.isEmpty() || !nPing.isEmpty();
            if (hasMetrics || retap > 1) return;
            setStatus("Confirmando inicio nperf con segundo toque...");
            tapWebViewCssPoint(cssX, cssY,
                () -> scheduleNperfStartRetap(cssX, cssY, retap + 1));
        }, 12000);
    }

    @SuppressWarnings("deprecation")
    private void tapWebViewCssPoint(float cssX, float cssY, Runnable afterTap) {
        if (webView == null || cssX <= 0 || cssY <= 0) {
            if (afterTap != null) afterTap.run();
            return;
        }
        webView.post(() -> {
            float scale = webView.getScale();
            if (scale <= 0) scale = 1f;
            float x = Math.max(1f, Math.min(webView.getWidth() - 1f, cssX * scale));
            float y = Math.max(1f, Math.min(webView.getHeight() - 1f, cssY * scale));
            long downTime = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            webView.dispatchTouchEvent(down);
            down.recycle();

            handler.postDelayed(() -> {
                long upTime = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent up = android.view.MotionEvent.obtain(
                    downTime, upTime, android.view.MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                webView.dispatchTouchEvent(up);
                up.recycle();
                if (afterTap != null) handler.postDelayed(afterTap, 250);
            }, 120);
        });
    }

    private String decodeJsResult(String value) {
        if (value == null || "null".equals(value)) return "";
        return value.replaceAll("^\\\"|\\\"$", "").replace("\\\\\\\"", "\\\"");
    }

    private float parseFloatSafe(String value) {
        try { return Float.parseFloat(value); }
        catch (Exception e) { return 0f; }
    }

// ── Polling nperf cada 3s'''

text, count = start_pattern.subn(lambda _: start_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"nPerf start block: expected 1 match, found {count}")

replace_once(
    "    private void startNperfPolling() {\n"
    "        handler.postDelayed(new Runnable() {",
    "    private void startNperfPolling() {\n"
    "        if (!nperfPollingStarted.compareAndSet(false, true)) return;\n"
    "        handler.postDelayed(new Runnable() {",
    "polling one-shot guard",
)

required = (
    "private void prepareNperfSession()",
    "private void tapWebViewCssPoint(",
    "Aceptando cookies nperf",
    "nperfPollingStarted.compareAndSet(false, true)",
    "setAcceptThirdPartyCookies(webView, true)",
)
for marker in required:
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

path.write_text(text, encoding="utf-8")
