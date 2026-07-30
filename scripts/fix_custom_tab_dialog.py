#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = path.read_text(encoding="utf-8")
broken = 'message + "\n\n" +'
fixed = 'message + "\\n\\n" +'
if broken in text:
    text = text.replace(broken, fixed, 1)
elif fixed not in text:
    raise RuntimeError("Custom Tab dialog string was not found")
path.write_text(text, encoding="utf-8")
