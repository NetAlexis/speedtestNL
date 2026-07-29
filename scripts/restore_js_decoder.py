#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/MainActivity.java")
text = path.read_text(encoding="utf-8")
marker = '    private boolean isNperfResultUrl(String url) {'
if text.count(marker) != 1:
    raise RuntimeError(f"Expected one result URL marker, found {text.count(marker)}")
if 'private String decodeJsResult(String value)' in text:
    raise RuntimeError("decodeJsResult already exists")

method = '''    private String decodeJsResult(String value) {
        try {
            Object decoded = new org.json.JSONTokener(
                value == null ? "null" : value).nextValue();
            if (decoded instanceof String) return (String) decoded;
            return decoded == null ? "" : decoded.toString();
        } catch (Exception error) {
            return value == null ? "" : value
                .replaceAll("^\\\"|\\\"$", "")
                .replace("\\\\\\\"", "\\\"");
        }
    }

'''
text = text.replace(marker, method + marker, 1)
path.write_text(text, encoding="utf-8")
