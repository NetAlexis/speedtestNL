#!/usr/bin/env python3
from pathlib import Path

parser_path = Path("app/src/main/java/com/netlife/speedtestnl/NperfScreenshotResultParser.java")
gradle_path = Path("app/build.gradle")
script_path = Path("scripts/fix_nperf_download_capacity.py")

parser = parser_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


parser = replace_once(
    parser,
    "            MetricValue throughputValue = parseThroughput(line.text);\n"
    "            if (throughputValue != null) {",
    "            MetricValue throughputValue = parseThroughput(line.text);\n"
    "            if (throughputValue != null &&\n"
    "                    !looksLikeServerCapacityLine(line.text)) {",
    "reject server capacity throughput candidates",
)

parser = replace_once(
    parser,
    "        return !containsAny(normalized,\n"
    "            \"aplicaciones para\", \"applications for\", \"descargue nuestras\",\n"
    "            \"download our\", \"adsl\", \"vdsl\", \"test de velocidad para tu conexion\",\n"
    "            \"speed test for your connection\", \"windows\", \"android\");",
    "        return !containsAny(normalized,\n"
    "            \"aplicaciones para\", \"applications for\", \"descargue nuestras\",\n"
    "            \"download our\", \"adsl\", \"vdsl\", \"test de velocidad para tu conexion\",\n"
    "            \"speed test for your connection\", \"windows\", \"android\") &&\n"
    "            !looksLikeServerCapacityLine(line.text);",
    "exclude capacity lines from useful OCR lines",
)

parser = replace_once(
    parser,
    "    private static double visualScore(Rect bounds, int width, int height,\n",
    '''    /**
     * nPerf prints the selected server above the result graph. That title may
     * include the server/plan capacity, for example:
     * "[EC] Ibarra - 3 Gb/s - Plus Internet de Alta Velocidad".
     *
     * OCR correctly reads "3 Gb/s" and converts it to 3000 Mb/s, but that is
     * not the measured download. A genuine result containing Gb/s is retained
     * when its line is only the value/unit or carries a metric label.
     */
    private static boolean looksLikeServerCapacityLine(String source) {
        String normalized = normalize(source);
        if (!containsAny(normalized, "gbit/s", "gb/s", "gbps")) return false;

        // Explicit metric labels make the value eligible even above 1 Gb/s.
        if (containsAny(normalized,
                "download", "descarga", "bajada", "debit descendant",
                "upload", "subida", "carga", "debit montant",
                "media", "average", "promedio")) {
            return false;
        }

        // Remove the numeric capacity and its unit. Remaining alphabetic text
        // means this is a server, ISP, city or commercial-plan description.
        String residue = normalized
            .replaceAll("[0-9]{1,4}(?:[.,][0-9]{1,3})?\\\\s*(?:gbit/s|gb/s|gbps)", " ")
            .replaceAll("[^a-z]+", " ")
            .replaceAll("\\\\s+", " ")
            .trim();
        return residue.length() > 2;
    }

    private static double visualScore(Rect bounds, int width, int height,
''',
    "server capacity classifier",
)

parser = replace_once(
    parser,
    "        double value = numeric(candidate.value);\n"
    "        if (value < 900.0d || !normalize(candidate.raw).contains(\"g\")) return false;",
    "        if (looksLikeServerCapacityLine(candidate.raw)) return true;\n"
    "        double value = numeric(candidate.value);\n"
    "        if (value < 900.0d || !normalize(candidate.raw).contains(\"g\")) return false;",
    "capacity candidate hard rejection",
)

gradle = replace_once(
    gradle,
    '        versionCode 10\n        versionName "2.0-nperf-ocr-anr-fix"',
    '        versionCode 11\n        versionName "2.1-nperf-download-capacity-fix"',
    "version bump",
)

for required in (
    "looksLikeServerCapacityLine(line.text)",
    "[EC] Ibarra - 3 Gb/s - Plus Internet de Alta Velocidad",
    'versionName "2.1-nperf-download-capacity-fix"',
):
    if required not in parser and required not in gradle:
        raise RuntimeError(f"missing required marker: {required}")

parser_path.write_text(parser, encoding="utf-8")
gradle_path.write_text(gradle, encoding="utf-8")
script_path.unlink()
