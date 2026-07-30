#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
text = path.read_text(encoding="utf-8")
old_1 = '''            AccessibilityNodeInfo startNode = findTextNode(root, false,
                new String[]{"iniciar test", "iniciar prueba", "start test",
                    "lancer le test"}, 0);'''
new_1 = '''            AccessibilityNodeInfo startNode = findTextNode(root, true,
                new String[]{"iniciar test", "iniciar prueba", "start test",
                    "lancer le test"}, 0);'''
old_2 = '''        AccessibilityNodeInfo node = findTextNode(root, false,
            new String[]{"iniciar test", "iniciar prueba", "start test",
                "lancer le test"}, 0);'''
new_2 = '''        AccessibilityNodeInfo node = findTextNode(root, true,
            new String[]{"iniciar test", "iniciar prueba", "start test",
                "lancer le test"}, 0);'''
for old, new, label in [(old_1, new_1, "start activation"), (old_2, new_2, "start visibility")]:
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise RuntimeError(f"{label} marker not found")
path.write_text(text, encoding="utf-8")
