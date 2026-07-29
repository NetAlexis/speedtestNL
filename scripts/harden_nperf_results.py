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
    '    private String  nServer    = "";\n'
    '    private String  nOperator  = "";',
    '    private String  nServer    = "";\n'
    '    private String  nOperator  = "";\n'
    '    private String  nResultId  = "";\n'
    '    private String  nResultUrl = "";',
    "nPerf result fields",
)

replace_once(
    '        nServer = ""; nOperator = ""; nDone = false;',
    '        nServer = ""; nOperator = ""; nResultId = ""; nResultUrl = ""; nDone = false;',
    "reset nPerf result fields",
)

replace_once(
    '        nServer = ""; nOperator = "";\n'
    '        nSaved.set(false); nErrorDetected.set(false);',
    '        nServer = ""; nOperator = ""; nResultId = ""; nResultUrl = "";\n'
    '        nSaved.set(false); nErrorDetected.set(false);\n'
    '        nperfPollingStarted.set(false);',
    "start nPerf result reset",
)

replace_once(
    '            "var p=point(n,ox,oy);try{n.click();}catch(x){}" +',
    '            "var p=point(n,ox,oy);" +',
    "avoid duplicate consent click",
)

old_target = '''            "var nodes=d.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span');" +
            "var words=['iniciar test','iniciar prueba','comenzar test','start test','lancer le test'];" +
            "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!visible(n))continue;" +
            "var t=(n.textContent||n.value||n.getAttribute('aria-label')||'').trim().toLowerCase();" +
            "for(var w=0;w<words.length;w++){if(t===words[w]||t.indexOf(words[w])>-1){" +
            "var p=point(n,ox,oy);return {state:'target',kind:'button',x:p.x,y:p.y};}}}" +'''
new_target = '''            "var nodes=d.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span');" +
            "var words=['iniciar test','iniciar prueba','comenzar test','start test','lancer le test'];" +
            "var bestNode=null,bestArea=1e20;" +
            "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!visible(n))continue;" +
            "var t=(n.textContent||n.value||n.getAttribute('aria-label')||'').trim().toLowerCase();" +
            "if(t.length>80)continue;var match=false;" +
            "for(var w=0;w<words.length;w++){if(t===words[w]||t.indexOf(words[w])>-1){match=true;break;}}" +
            "if(match){var nr=n.getBoundingClientRect(),area=nr.width*nr.height;" +
            "if(area>0&&area<bestArea){bestNode=n;bestArea=area;}}}" +
            "if(bestNode){var p=point(bestNode,ox,oy);" +
            "return {state:'target',kind:'button',x:p.x,y:p.y};}" +'''
replace_once(old_target, new_target, "precise nPerf start target")

replace_once(
    '} else if ("nperf".equals(phase) && url.contains("/result") && !nSaved.get()) {\n'
    '                    if (nSaved.compareAndSet(false, true))',
    '} else if ("nperf".equals(phase) && isNperfResultUrl(url) && !nSaved.get()) {\n'
    '                    captureNperfResultUrl(url);\n'
    '                    if (nSaved.compareAndSet(false, true))',
    "nPerf page-finished result URL",
)

replace_once(
    '                String curUrl = webView.getUrl();\n'
    '                if (curUrl != null && curUrl.contains("/result")) {\n'
    '                    if (nSaved.compareAndSet(false, true)) {',
    '                String curUrl = webView.getUrl();\n'
    '                if (isNperfResultUrl(curUrl)) {\n'
    '                    captureNperfResultUrl(curUrl);\n'
    '                    if (nSaved.compareAndSet(false, true)) {',
    "nPerf polling result URL",
)

replace_once(
    '                        "  var b=document.body?document.body.innerHTML:\'\';" +\n'
    '                        "  var hasError=b.indexOf(\'ERREUR\')>-1||b.indexOf(\'ERROR\')>-1" +\n'
    '                        "    ||b.indexOf(\'error\')>-1&&b.indexOf(\'test-error\')>-1;" +\n'
    '                        // Detectar si la prueba terminó: aparece "Reiniciar"\n'
    '                        "  var done=b.indexOf(\'Reiniciar\')>-1" +\n'
    '                        "    ||b.indexOf(\'Restart\')>-1||b.indexOf(\'Reinitier\')>-1;" +',
    '                        "  var b=document.body?document.body.innerHTML:\'\';" +\n'
    '                        "  var bt=(document.body?document.body.innerText:\'\').toLowerCase();" +\n'
    '                        "  var hasError=b.indexOf(\'ERREUR\')>-1||b.indexOf(\'ERROR\')>-1" +\n'
    '                        "    ||b.indexOf(\'error\')>-1&&b.indexOf(\'test-error\')>-1;" +\n'
    '                        "  var done=bt.indexOf(\'haz click aquí para probar de nuevo\')>-1" +\n'
    '                        "    ||bt.indexOf(\'haz clic aquí para probar de nuevo\')>-1" +\n'
    '                        "    ||bt.indexOf(\'reiniciar\')>-1||bt.indexOf(\'restart\')>-1" +\n'
    '                        "    ||bt.indexOf(\'reinitier\')>-1;" +',
    "current nPerf completion text",
)

replace_once(
    '            boolean finished = "true".equals(done) || (hasDl && hasUl);',
    '            boolean finished = "true".equals(done) && hasDl && hasUl;',
    "prevent premature nPerf completion",
)

helper_marker = '    // ── Polling nperf cada 3s ─────────────────────────────────────────────\n'
helpers = '''    private boolean isNperfResultUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("nperf.com") &&
            (lower.contains("/r/") || lower.contains("/result"));
    }

    private void captureNperfResultUrl(String url) {
        if (url == null || url.isEmpty()) return;
        nResultUrl = url;
        Matcher matcher = Pattern.compile("/r/(\\\\d+)(?:-|/|$)").matcher(url);
        if (matcher.find()) nResultId = matcher.group(1);
    }

'''
replace_once(helper_marker, helpers + helper_marker, "nPerf result URL helpers")

extract_pattern = re.compile(
    r"    private void extractNperfMetrics\(\) \{.*?\n    \}\n\n    // ═+\n    // COMPLETAR SPEEDTEST",
    re.DOTALL,
)
extract_replacement = r'''    private void extractNperfMetrics() {
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
    // COMPLETAR SPEEDTEST'''
text, count = extract_pattern.subn(lambda _: extract_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"extract nPerf result method: expected 1 match, found {count}")

replace_once(
    '            "  Operador     : " + (nOperator.isEmpty() ? "N/A" : nOperator) + "\\n\\n" +',
    '            "  Operador     : " + (nOperator.isEmpty() ? "N/A" : nOperator) + "\\n" +\n'
    '            "  Result ID    : " + (nResultId.isEmpty() ? "N/A" : nResultId) + "\\n" +\n'
    '            "  URL          : " + (nResultUrl.isEmpty() ? "N/A" : nResultUrl) + "\\n\\n" +',
    "nPerf result metadata in TXT",
)

replace_once(
    '            tvResultId.setText("nperf — " + (nServer.isEmpty() ? "midiendo..." : nServer));',
    '            tvResultId.setText(!nResultId.isEmpty()\n'
    '                ? "nPerf ID: " + nResultId\n'
    '                : "nperf — " + (nServer.isEmpty() ? "midiendo..." : nServer));',
    "nPerf result ID panel",
)

required = (
    "private boolean isNperfResultUrl(String url)",
    "private void captureNperfResultUrl(String url)",
    "haz click aquí para probar de nuevo",
    "boolean finished = \"true\".equals(done) && hasDl && hasUl;",
    '"  Result ID    : " + (nResultId.isEmpty()',
)
for marker in required:
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

path.write_text(text, encoding="utf-8")
