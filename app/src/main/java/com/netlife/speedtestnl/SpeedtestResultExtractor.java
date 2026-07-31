package com.netlife.speedtestnl;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;

/** Extracts final Speedtest metrics from the result page without relying on ads. */
final class SpeedtestResultExtractor {

    static final class Metrics {
        String download = "";
        String upload = "";
        String ping = "";
        String jitter = "";
    }

    private SpeedtestResultExtractor() { }

    static String javascript() {
        return "(function(){try{" +
            "var body=document.body?document.body.innerText:'';" +
            "var lines=body.split(/\\n+/).map(function(s){return s.trim();})" +
            ".filter(function(s){return s.length>0;});" +
            "function norm(s){return (s||'').toLowerCase()" +
            ".normalize('NFD').replace(/[\\u0300-\\u036f]/g,'')" +
            ".replace(/\\s+/g,' ').trim();}" +
            "function num(s){var m=(s||'').match(/([0-9]+(?:[.,][0-9]+)?)/);" +
            "return m?m[1].replace(',','.'):'';}" +
            "function direct(selectors,attrs){for(var i=0;i<selectors.length;i++){" +
            "var els=document.querySelectorAll(selectors[i]);for(var j=0;j<els.length;j++){" +
            "var e=els[j];if(!e||e.offsetParent===null)continue;var values=[];" +
            "for(var a=0;a<attrs.length;a++)values.push(e.getAttribute(attrs[a])||'');" +
            "values.push(e.textContent||'');for(var v=0;v<values.length;v++){" +
            "var n=num(values[v]);if(n&&parseFloat(n)>0)return n;}}}return ''; }" +
            "function isLabel(line,labels){var n=norm(line);if(n.length>52||" +
            "/up to|hasta|maximum|maximo|recommended|recomendado/.test(n))return false;" +
            "for(var i=0;i<labels.length;i++){var l=labels[i];" +
            "if(n===l||n.indexOf(l+' ')===0||n.lastIndexOf(' '+l)===n.length-l.length-1)" +
            "return true;}return false;}" +
            "function otherLabel(line){return isLabel(line,['download','descarga','bajada'," +
            "'upload','subida','carga','ping','latency','latencia','jitter']);}" +
            "function near(labels,kind){for(var i=0;i<lines.length;i++){" +
            "if(!isLabel(lines[i],labels))continue;var win=lines[i];" +
            "for(var j=i;j<Math.min(lines.length,i+7);j++){" +
            "if(j>i){if(j>i+1&&otherLabel(lines[j]))break;win+=' '+lines[j];}" +
            "var p;if(kind==='speed'){" +
            "p=win.match(/([0-9]+(?:[.,][0-9]+)?)\\s*(?:gbps|gb\\/s|gbit\\/s|mbps|mb\\/s|mbit\\/s)/i);" +
            "if(p){var val=parseFloat(p[1].replace(',','.'));" +
            "if(/g(?:bps|b\\/s|bit\\/s)/i.test(p[0]))val*=1000;return ''+val;}" +
            "p=win.match(/(?:gbps|gb\\/s|gbit\\/s|mbps|mb\\/s|mbit\\/s)" +
            "\\s*[:=-]?\\s*([0-9]+(?:[.,][0-9]+)?)/i);" +
            "if(p){var val2=parseFloat(p[1].replace(',','.'));" +
            "if(/^g/i.test(p[0]))val2*=1000;return ''+val2;}" +
            "}else{" +
            "p=win.match(/([0-9]+(?:[.,][0-9]+)?)\\s*ms/i);if(p)return p[1].replace(',','.');" +
            "p=win.match(/ms\\s*[:=-]?\\s*([0-9]+(?:[.,][0-9]+)?)/i);" +
            "if(p)return p[1].replace(',','.');}" +
            "}}return ''; }" +
            "var dl=direct(['[data-download-speed]','#download-value'," +
            "'.download-speed','[class*=download-speed]']," +
            "['data-download-speed','data-value','value']);" +
            "var ul=direct(['[data-upload-speed]','#upload-value'," +
            "'.upload-speed','[class*=upload-speed]']," +
            "['data-upload-speed','data-value','value']);" +
            "var pg=direct(['[data-latency]','[data-ping]','#ping-value'," +
            "'.ping-speed','[class*=latency-value]']," +
            "['data-latency','data-ping','data-value','value']);" +
            "var jt=direct(['[data-jitter]','#jitter-value','.jitter-speed'," +
            "'[class*=jitter-value]'],['data-jitter','data-value','value']);" +
            "if(!dl)dl=near(['download','descarga','bajada'],'speed');" +
            "if(!ul)ul=near(['upload','subida','carga'],'speed');" +
            "if(!pg)pg=near(['ping','latency','latencia'],'time');" +
            "if(!jt)jt=near(['jitter'],'time');" +
            "return JSON.stringify({dl:dl,ul:ul,pg:pg,jt:jt});" +
            "}catch(e){return JSON.stringify({error:String(e)});}})()";
    }

    static Metrics parse(String rawValue) {
        Metrics metrics = new Metrics();
        try {
            Object decoded = new JSONTokener(rawValue == null ? "null" : rawValue)
                .nextValue();
            for (int i = 0; i < 2 && decoded instanceof String; i++) {
                decoded = new JSONTokener((String) decoded).nextValue();
            }
            if (!(decoded instanceof JSONObject)) return metrics;
            JSONObject object = (JSONObject) decoded;
            metrics.download = decimal(object.optString("dl", ""));
            metrics.upload = decimal(object.optString("ul", ""));
            metrics.ping = decimal(object.optString("pg", ""));
            metrics.jitter = decimal(object.optString("jt", ""));
        } catch (Exception ignored) { }
        return metrics;
    }

    static boolean hasRequired(Metrics metrics) {
        return metrics != null && positive(metrics.download) &&
            positive(metrics.upload) && positive(metrics.ping);
    }

    static boolean positive(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" :
                value.replace(',', '.').trim());
            return parsed > 0.0d && parsed < 100000.0d;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String decimal(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" :
                value.replace(',', '.').trim());
            if (parsed <= 0.0d || Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return "";
            }
            return BigDecimal.valueOf(parsed).stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
