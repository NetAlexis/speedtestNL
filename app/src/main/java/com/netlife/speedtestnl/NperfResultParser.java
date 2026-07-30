package com.netlife.speedtestnl;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only labelled nPerf result metrics and ignores gauge scales or ads. */
final class NperfResultParser {

    private static final Pattern THROUGHPUT_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps)");
    private static final Pattern THROUGHPUT_UNIT_VALUE = Pattern.compile(
        "(?i)(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps)\\s*[:=-]?\\s*" +
        "([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern LATENCY_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]+(?:[.,][0-9]+)?)\\s*ms");
    private static final Pattern LATENCY_UNIT_VALUE = Pattern.compile(
        "(?i)ms\\s*[:=-]?\\s*([0-9]+(?:[.,][0-9]+)?)");

    private NperfResultParser() { }

    static NperfBrowserCoordinator.Result parse(List<String> lines, String joined) {
        NperfBrowserCoordinator.Result result =
            new NperfBrowserCoordinator.Result();

        // The generic parser is retained only for metadata. Its metric regexes
        // are intentionally not trusted because the nPerf gauge exposes values
        // such as "1 Gb/s" next to the words Download and Upload.
        NperfBrowserCoordinator.Result metadata =
            NperfBrowserCoordinator.parseSharedText(joined);
        result.server = metadata.server;
        result.operator = metadata.operator;
        result.resultId = metadata.resultId;
        result.resultUrl = metadata.resultUrl;

        result.download = metricNearLabel(lines,
            new String[]{"download", "descarga", "bajada"}, true);
        result.upload = metricNearLabel(lines,
            new String[]{"upload", "subida", "carga"}, true);
        result.latency = metricNearLabel(lines,
            new String[]{"latency", "latencia", "ping"}, false);
        result.jitter = metricNearLabel(lines,
            new String[]{"jitter"}, false);
        return result;
    }

    static boolean hasRequiredMetrics(NperfBrowserCoordinator.Result result) {
        return result != null && positive(result.download) &&
            positive(result.upload) && positive(result.latency);
    }

    static boolean positive(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" :
                value.replace(',', '.').trim());
            return parsed > 0.0d && parsed < 100000.0d;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String metricNearLabel(List<String> lines,
            String[] labels, boolean throughput) {
        if (lines == null || lines.isEmpty()) return "";

        for (int i = 0; i < lines.size(); i++) {
            String labelLine = normalize(lines.get(i));
            if (!isMetricLabelLine(labelLine, labels)) continue;

            StringBuilder window = new StringBuilder(lines.get(i));
            String direct = parseWindow(window.toString(), throughput);
            if (!direct.isEmpty()) return direct;

            int limit = Math.min(lines.size(), i + 7);
            for (int j = i + 1; j < limit; j++) {
                String candidate = normalize(lines.get(j));
                if (j > i + 1 && isAnyMetricLabel(candidate)) break;
                window.append(' ').append(lines.get(j));
                String parsed = parseWindow(window.toString(), throughput);
                if (!parsed.isEmpty()) return parsed;
            }
        }
        return "";
    }

    private static boolean isMetricLabelLine(String line, String[] labels) {
        if (line.isEmpty() || line.length() > 48 || containsAny(line,
                "up to", "hasta", "maximum", "maximo", "recommended",
                "recomendado", "application", "aplicacion")) {
            return false;
        }
        for (String label : labels) {
            String expected = normalize(label);
            if (line.equals(expected) || line.startsWith(expected + " ") ||
                    line.endsWith(" " + expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyMetricLabel(String line) {
        return isMetricLabelLine(line,
            new String[]{"download", "descarga", "bajada", "upload",
                "subida", "carga", "latency", "latencia", "ping", "jitter"});
    }

    private static String parseWindow(String source, boolean throughput) {
        if (source == null || source.isEmpty()) return "";
        if (throughput) {
            Matcher valueUnit = THROUGHPUT_VALUE_UNIT.matcher(source);
            if (valueUnit.find()) {
                return throughputMbps(valueUnit.group(1), valueUnit.group(2));
            }
            Matcher unitValue = THROUGHPUT_UNIT_VALUE.matcher(source);
            if (unitValue.find()) {
                return throughputMbps(unitValue.group(2), unitValue.group(1));
            }
            return "";
        }

        Matcher valueUnit = LATENCY_VALUE_UNIT.matcher(source);
        if (valueUnit.find()) return decimal(valueUnit.group(1));
        Matcher unitValue = LATENCY_UNIT_VALUE.matcher(source);
        return unitValue.find() ? decimal(unitValue.group(1)) : "";
    }

    private static String throughputMbps(String numeric, String unit) {
        try {
            double value = Double.parseDouble(numeric.replace(',', '.'));
            String normalizedUnit = normalize(unit);
            if (normalizedUnit.startsWith("g")) value *= 1000.0d;
            return value > 0.0d ? decimal(value) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String decimal(String value) {
        try {
            return decimal(Double.parseDouble(value.replace(',', '.')));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String decimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0d) {
            return "";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(normalize(value))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value,
            Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ").trim();
    }
}
