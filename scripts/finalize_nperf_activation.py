#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfAutomation.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "                        activateTarget(token, x, y, viewportWidth, viewportHeight,\n"
    "                            true, false, () -> inspect(token, 1100L));",
    "                        activateTarget(token, x, y, viewportWidth, viewportHeight,\n"
    "                            true, true, () -> inspect(token, 1100L));",
    "consent native touch fallback",
)

replace_once(
    "        if (startActivationCount >= MAX_START_ACTIVATIONS) {\n"
    "            notifyPollingOnce();\n"
    "            showManualNoticeOnce();\n"
    "            inspect(token, 7000L);\n"
    "            return;\n"
    "        }",
    "        if (startActivationCount >= MAX_START_ACTIVATIONS) {\n"
    "            generation++;\n"
    "            listener.onEngineError(\"nPerf no respondió a las activaciones de Iniciar test\");\n"
    "            return;\n"
    "        }",
    "bounded automatic activation failure",
)

old_order = '''            "if(best){try{best.setAttribute('tabindex','0');best.focus();}catch(ignore){}" +
            "return result('start','button',point(best,ox,oy));}" +
            "var canvases=d.querySelectorAll('canvas'),canvas=null,canvasArea=0;" +
            "for(var k=0;k<canvases.length;k++){var cv=canvases[k],cr=cv.getBoundingClientRect();" +
            "var ca=cr.width*cr.height;if(visible(cv)&&cr.width>130&&cr.height>130&&ca>canvasArea){" +
            "canvas=cv;canvasArea=ca;}}" +
            "if(canvas)return result('canvas','canvas',point(canvas,ox,oy));" +
            "if(body.indexOf('inicializando')>-1||body.indexOf('initializing')>-1)" +
            "return result('initializing','',null);" +
'''
new_order = '''            "if(best){try{best.setAttribute('tabindex','0');best.focus();}catch(ignore){}" +
            "return result('start','button',point(best,ox,oy));}" +
            "if(body.indexOf('inicializando')>-1||body.indexOf('initializing')>-1)" +
            "return result('initializing','',null);" +
            "var canvases=d.querySelectorAll('canvas'),canvas=null,canvasArea=0;" +
            "for(var k=0;k<canvases.length;k++){var cv=canvases[k],cr=cv.getBoundingClientRect();" +
            "var ca=cr.width*cr.height;if(visible(cv)&&cr.width>130&&cr.height>130&&ca>canvasArea){" +
            "canvas=cv;canvasArea=ca;}}" +
            "if(canvas)return result('canvas','canvas',point(canvas,ox,oy));" +
'''
replace_once(old_order, new_order, "wait for nPerf initialization before canvas activation")

replace_once(
    "        if (scanCount >= MAX_ACTIVE_SCANS) {\n"
    "            showManualNoticeOnce();\n"
    "            inspect(token, 8000L);\n"
    "        } else {",
    "        if (scanCount >= MAX_ACTIVE_SCANS) {\n"
    "            generation++;\n"
    "            listener.onEngineError(\"nPerf no presentó un control de inicio operativo\");\n"
    "        } else {",
    "bounded no-target failure",
)

for marker in (
    "true, true, () -> inspect(token, 1100L)",
    "nPerf no respondió a las activaciones de Iniciar test",
    "nPerf no presentó un control de inicio operativo",
):
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

path.write_text(text, encoding="utf-8")
