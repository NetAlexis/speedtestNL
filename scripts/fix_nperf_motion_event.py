#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfAutomation.java")
text = path.read_text(encoding="utf-8")
for line in ("        down.setPressure(1f);\n", "            up.setPressure(1f);\n"):
    count = text.count(line)
    if count != 1:
        raise RuntimeError(f"expected exactly one occurrence of {line!r}, found {count}")
    text = text.replace(line, "", 1)
path.write_text(text, encoding="utf-8")
