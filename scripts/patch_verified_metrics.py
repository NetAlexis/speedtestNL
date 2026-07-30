#!/usr/bin/env python3
from pathlib import Path

path = Path('app/src/main/java/com/netlife/speedtestnl/MainActivity.java')
text = path.read_text(encoding='utf-8')
old = 'Pattern.compile("result/([\\w-]+)")'
new = 'Pattern.compile("result/([\\\\w-]+)")'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise RuntimeError('Speedtest result URL regex marker not found')
path.write_text(text, encoding='utf-8')
