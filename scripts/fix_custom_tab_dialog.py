#!/usr/bin/env python3
from pathlib import Path

main_path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
broken = 'message + "\n\n" +'
fixed = 'message + "\\n\\n" +'
if broken in main:
    main = main.replace(broken, fixed, 1)
elif fixed not in main:
    raise RuntimeError("Custom Tab dialog string was not found")
main_path.write_text(main, encoding="utf-8")

browser_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserActivity.java")
browser = browser_path.read_text(encoding="utf-8")
replacements = {
    "                setResult(Activity.RESULT_OK, result);":
        "                NperfBrowserActivity.this.setResult(Activity.RESULT_OK, result);",
    "                setResult(Activity.RESULT_CANCELED, result);":
        "                NperfBrowserActivity.this.setResult(Activity.RESULT_CANCELED, result);",
}
for old, new in replacements.items():
    if old in browser:
        browser = browser.replace(old, new, 1)
    elif new not in browser:
        raise RuntimeError(f"Missing browser result marker: {old}")
browser_path.write_text(browser, encoding="utf-8")
