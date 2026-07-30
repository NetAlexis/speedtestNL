#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.accessibilityservice.GestureDescription;\n",
    "import android.accessibilityservice.GestureDescription;\n"
    "import android.annotation.SuppressLint;\n",
    "SuppressLint import",
)
replace_once(
    "    private void requestVisualResultOcr(boolean explicitComplete, long now) {\n",
    "    @SuppressLint(\"NewApi\")\n"
    "    private void requestVisualResultOcr(boolean explicitComplete, long now) {\n",
    "screenshot API annotation",
)
replace_once(
    "    private void processScreenshotResult(ScreenshotResult screenshotResult,\n",
    "    @SuppressLint(\"NewApi\")\n"
    "    private void processScreenshotResult(ScreenshotResult screenshotResult,\n",
    "screenshot result API annotation",
)

if text.count('@SuppressLint("NewApi")') != 2:
    raise RuntimeError("expected exactly two localized NewApi suppressions")
if "Build.VERSION.SDK_INT < Build.VERSION_CODES.R" not in text:
    raise RuntimeError("runtime API 30 guard is missing")

path.write_text(text, encoding="utf-8")
