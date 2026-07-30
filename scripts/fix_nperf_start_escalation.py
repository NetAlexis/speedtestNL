#!/usr/bin/env python3
from pathlib import Path

service_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
gradle_path = Path("app/build.gradle")
service = service_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)

service = replace_once(
    service,
    "    private static final long START_RETRY_INTERVAL_MS = 9000L;",
    "    private static final long START_RETRY_INTERVAL_MS = 8000L;",
    "start retry interval",
)
service = replace_once(
    service,
    "    private static final int MAX_START_ATTEMPTS = 1;",
    "    private static final int MAX_START_ATTEMPTS = 3;",
    "bounded start attempts",
)

old_live = '''        boolean confirmedByDisappearance = startMissingObservations >= 2 &&
            now - lastStartAttemptAt >= 1500L;
        boolean confirmedByLiveData = newThroughput &&
            now - lastStartAttemptAt >= 3000L;
'''
new_live = '''        boolean confirmedByDisappearance = startMissingObservations >= 2 &&
            now - lastStartAttemptAt >= 1500L;
        boolean newLatency = NperfResultParser.positive(sanitized.latency);
        boolean confirmedByLiveData = (newThroughput || newLatency) &&
            now - lastStartAttemptAt >= 3000L;
'''
service = replace_once(service, old_live, new_live,
    "confirm start from new latency or throughput")

old_activation = '''    private boolean activateStartNode(AccessibilityNodeInfo node) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;

        if (performClickOnNodeOrParent(node)) {
            markStartRequested("ACCESSIBILITY_CLICK");
            return true;
        }

        Rect bounds = bestTapBounds(node);
        if (!isUsableBounds(bounds)) return false;
        return dispatchTap(bounds.centerX(), bounds.centerY(),
            "START_TEXT_BOUNDS", true);
    }
'''
new_activation = '''    private boolean activateStartNode(AccessibilityNodeInfo node) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false;

        // Chrome may report ACTION_CLICK as accepted even when the nPerf canvas
        // does not receive it. Use that semantic click only for the first try;
        // subsequent bounded retries are real Android gestures on the exact
        // visible text/control bounds.
        if (startAttempts == 0 && performClickOnNodeOrParent(node)) {
            markStartRequested("ACCESSIBILITY_CLICK");
            return true;
        }

        Rect bounds = bestTapBounds(node);
        if (!isUsableBounds(bounds)) return false;
        String source = startAttempts == 0
            ? "START_TEXT_BOUNDS"
            : "START_RETRY_TEXT_BOUNDS_" + (startAttempts + 1);
        return dispatchTap(bounds.centerX(), bounds.centerY(), source, true);
    }
'''
service = replace_once(service, old_activation, new_activation,
    "escalate semantic click to native touch")

old_fallback = '''        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float[] verticalFractions = {0.39f, 0.43f, 0.47f};
        int index = Math.min(startAttempts, verticalFractions.length - 1);
'''
new_fallback = '''        DisplayMetrics metrics = getResources().getDisplayMetrics();
        // In Chrome Custom Tabs the nPerf gauge is normally in the upper third.
        // These positions are used only when no accessible start node exists.
        float[] verticalFractions = {0.27f, 0.32f, 0.38f};
        int index = Math.min(startAttempts, verticalFractions.length - 1);
'''
service = replace_once(service, old_fallback, new_fallback,
    "correct Custom Tab visual fallback positions")

service = replace_once(
    service,
    '        status("START_REQUESTED", "Activación " + startAttempts + "/" +\n            MAX_START_ATTEMPTS + " enviada; verificando inicio real...");',
    '        status("START_REQUESTED", "Activación controlada " + startAttempts + "/" +\n            MAX_START_ATTEMPTS + " enviada; verificando inicio real...");',
    "start status",
)

for marker in (
    "private static final int MAX_START_ATTEMPTS = 3;",
    "START_RETRY_TEXT_BOUNDS_",
    "boolean newLatency = NperfResultParser.positive(sanitized.latency);",
    "float[] verticalFractions = {0.27f, 0.32f, 0.38f};",
):
    if marker not in service:
        raise RuntimeError(f"missing service marker: {marker}")

service_path.write_text(service, encoding="utf-8")

gradle = replace_once(
    gradle,
    '        versionCode 11\n        versionName "2.1-nperf-download-capacity-fix"',
    '        versionCode 12\n        versionName "2.2-nperf-start-escalation"',
    "version bump",
)
gradle_path.write_text(gradle, encoding="utf-8")
