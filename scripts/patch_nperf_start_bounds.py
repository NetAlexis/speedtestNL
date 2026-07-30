#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfBrowserAutomationService.java")
text = path.read_text(encoding="utf-8")
old = '''    private Rect bestTapBounds(AccessibilityNodeInfo node) {
        Rect best = new Rect();
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null && depth < 7; depth++) {
                Rect candidate = new Rect();
                current.getBoundsInScreen(candidate);
                if (isUsableBounds(candidate) &&
                        (best.isEmpty() || candidate.width() * candidate.height() >
                            best.width() * best.height())) {
                    best.set(candidate);
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        return best;
    }
'''
new = '''    private Rect bestTapBounds(AccessibilityNodeInfo node) {
        Rect best = new Rect();
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        try {
            for (int depth = 0; current != null && depth < 7; depth++) {
                Rect candidate = new Rect();
                current.getBoundsInScreen(candidate);
                boolean plausibleControl = isUsableBounds(candidate) &&
                    candidate.width() <= metrics.widthPixels * 0.90f &&
                    candidate.height() <= metrics.heightPixels * 0.30f;
                if (plausibleControl) {
                    best.set(candidate);
                    break;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        return best;
    }
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise RuntimeError("bestTapBounds block not found")
path.write_text(text, encoding="utf-8")
