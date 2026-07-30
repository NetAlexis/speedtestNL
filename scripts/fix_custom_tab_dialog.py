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

import_marker = "import androidx.browser.customtabs.CustomTabsIntent;\n"
compat_import = import_marker + "import androidx.core.content.ContextCompat;\n"
if compat_import not in browser:
    if import_marker not in browser:
        raise RuntimeError("CustomTabsIntent import marker not found")
    browser = browser.replace(import_marker, compat_import, 1)

old_receiver = '''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
'''
new_receiver = '''        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
'''
if old_receiver in browser:
    browser = browser.replace(old_receiver, new_receiver, 1)
elif new_receiver not in browser:
    raise RuntimeError("Dynamic receiver registration marker not found")

browser_path.write_text(browser, encoding="utf-8")
