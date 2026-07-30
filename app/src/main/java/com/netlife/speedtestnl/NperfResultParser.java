package com.netlife.speedtestnl;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses only the live nPerf metrics. Average/Media rows, gauge scales,
 * advertisements and server capacity values are never accepted as results.
 */
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

        // The generic parser is retained only for metadata. Its numeric output
        // is not trusted because the nPerf page also publishes server limits,
        // gauge ticks and average values.
        NperfBrowserCoordinator.Result metadata =
            NperfBrowserCoordinator.parseSharedText(joined);
        result.server = metadata.server;
        result.operator = metadata.operator;
        result.resultId = metadata.resultId;
        result.resultUrl = metadata.resultUrl;

        result.download = metricNearLabel(lines,
            new String[]{"download", "descarga", "bajada", "debit descendant"}, true);
        result.upload = metricNearLabel(lines,
            new String[]{"upload", "subida", "carga", "debit montant"}, true);
        result.latency = metricNearLabel(lines,
            new String[]{"latency", "latencia", "ping"}, false);

        // The public nPerf panel used by this app does not expose jitter as a
        // primary test metric. The value shown below ping as "Media" is an
        // average latency and must never be written as jitter.
        result.jitter = "";
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

            String direct = parseWindow(lines.get(i), throughput);
            if (!direct.isEmpty() && !isAverageLine(labelLine)) return direct;

            boolean skipNextNumeric = false;
            int limit = Math.min(lines.size(), i + 9);
            for (int j = i + 1; j < limit; j++) {
                String raw = lines.get(j) == null ? "" : lines.get(j).trim();
                String candidate = normalize(raw);
                if (candidate.isEmpty()) continue;
                if (j > i + 1 && isAnyMetricLabel(candidate)) break;

                // nPerf renders an average row as either "Media 152 Mb/s" or
                // as two accessibility nodes: "Media" then "152 Mb/s".
                if (isAverageLine(candidate)) {
                    skipNextNumeric = !containsMetricValue(raw, throughput);
                    continue;
                }

                String parsed = parseWindow(raw, throughput);
                if (skipNextNumeric && !parsed.isEmpty()) {
                    skipNextNumeric = false;
                    continue;
                }
                skipNextNumeric = false;
                if (!parsed.isEmpty()) return parsed;

                // Some browsers split the number and unit into adjacent nodes.
                if (j + 1 < limit) {
                    String nextRaw = lines.get(j + 1) == null ? "" : lines.get(j + 1).trim();
                    String next = normalize(nextRaw);
                    if (!isAverageLine(next) && !isAnyMetricLabel(next)) {
                        parsed = parseWindow(raw + " " + nextRaw, throughput);
                        if (!parsed.isEmpty()) return parsed;
                    }
                }
            }
        }
        return "";
    }

    private static boolean containsMetricValue(String source, boolean throughput) {
        return !parseWindow(source, throughput).isEmpty();
    }

    private static boolean isAverageLine(String line) {
        return containsAny(line, "media", "average", "avg", "promedio", "moyenne");
    }

    private static boolean isMetricLabelLine(String line, String[] labels) {
        if (line.isEmpty() || line.length() > 64 || isAverageLine(line) || containsAny(line,
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
            new String[]{"download", "descarga", "bajada", "debit descendant",
                "upload", "subida", "carga", "debit montant",
                "latency", "latencia", "ping"});
    }

    private static String parseWindow(String source, boolean throughput) {
        if (source == null || source.isEmpty() || isAverageLine(normalize(source))) return "";
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
            return value > 0.0d && value < 100000.0d ? decimal(value) : "";
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
