package com.netlife.speedtestnl;

import android.graphics.Rect;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the final nPerf result card from ML Kit OCR bounding boxes.
 *
 * Contract for the public nPerf panel:
 *  - the blue/down-arrow value is download;
 *  - the up-arrow value is upload;
 *  - ping/latency is the third primary value;
 *  - every Media/Average/Promedio value is excluded;
 *  - nPerf jitter is intentionally empty.
 */
final class NperfScreenshotResultParser {

    static final class Line {
        final String text;
        final Rect bounds;

        Line(String text, Rect bounds) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        }
    }

    private enum Role { DOWNLOAD, UPLOAD, LATENCY }

    private static final Pattern THROUGHPUT_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]{1,5}(?:[.,][0-9]{1,3})?)\\s*" +
        "(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps|mbits/s|mbit\\s*/\\s*s|mbit|mbits)");
    private static final Pattern THROUGHPUT_UNIT_VALUE = Pattern.compile(
        "(?i)(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps|mbits/s|mbit\\s*/\\s*s|mbit|mbits)" +
        "\\s*[:=-]?\\s*([0-9]{1,5}(?:[.,][0-9]{1,3})?)");
    private static final Pattern LATENCY_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]{1,5}(?:[.,][0-9]{1,3})?)\\s*m(?:illi)?s(?:ec(?:ond)?s?)?");
    private static final Pattern LATENCY_UNIT_VALUE = Pattern.compile(
        "(?i)m(?:illi)?s(?:ec(?:ond)?s?)?\\s*[:=-]?\\s*" +
        "([0-9]{1,5}(?:[.,][0-9]{1,3})?)");
    private static final Pattern NUMBER_ONLY = Pattern.compile(
        "^[\\s↓⬇⇩▼⭣▾↑⬆⇧▲⭡▴]*" +
        "([0-9]{1,5}(?:[.,][0-9]{1,3})?)" +
        "[\\s↓⬇⇩▼⭣▾↑⬆⇧▲⭡▴]*$");
    private static final Pattern AVERAGE_LABEL = Pattern.compile(
        "(?iu)\\b(m[eé]dia|average|avg|promedio|moyenne)\\b");

    private static final class Candidate {
        final String value;
        final String raw;
        final Rect bounds;
        final Role labelledRole;
        final double score;

        Candidate(String value, String raw, Rect bounds, Role labelledRole,
                double score) {
            this.value = value;
            this.raw = raw;
            this.bounds = new Rect(bounds);
            this.labelledRole = labelledRole;
            this.score = score;
        }
    }

    private static final class Marker {
        final Role role;
        final Rect bounds;

        Marker(Role role, Rect bounds) {
            this.role = role;
            this.bounds = new Rect(bounds);
        }
    }

    private NperfScreenshotResultParser() { }

    static NperfBrowserCoordinator.Result parse(List<Line> lines,
            int imageWidth, int imageHeight) {
        NperfBrowserCoordinator.Result result =
            new NperfBrowserCoordinator.Result();
        if (lines == null || lines.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return result;
        }

        lines = expandSplitMetricLines(lines, imageWidth, imageHeight);

        List<Candidate> throughput = new ArrayList<>();
        List<Candidate> latency = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        List<Rect> averageMarkers = new ArrayList<>();

        // OCR frequently splits “Media” and its number into separate nodes.
        // Record every average label first so its detached numeric value can
        // never be selected later as download, upload or latency.
        for (Line line : lines) {
            if (line == null || line.bounds.isEmpty()) continue;
            if (isAverageLine(normalize(line.text))) {
                averageMarkers.add(new Rect(line.bounds));
            }
        }

        for (Line line : lines) {
            if (!isUsefulLine(line, imageWidth, imageHeight)) continue;
            String primaryText = stripAveragePortion(line.text);
            String normalized = normalize(primaryText);
            if (normalized.isEmpty()) continue;

            Role role = roleFor(primaryText);
            if (role != null) markers.add(new Marker(role, line.bounds));
            boolean explicitPrimaryRole = role == Role.DOWNLOAD ||
                role == Role.UPLOAD || role == Role.LATENCY;
            MetricValue throughputValue = parseThroughput(primaryText);
            String latencyValue = parseLatency(primaryText);
            boolean hasPrimaryMetric = throughputValue != null ||
                !latencyValue.isEmpty();
            boolean primaryBeforeAverage = hasPrimaryMetric &&
                !primaryText.equals(line.text == null ? "" : line.text.trim());
            if (!explicitPrimaryRole && !primaryBeforeAverage &&
                    isDetachedAverageValue(line.bounds,
                        averageMarkers, imageWidth, imageHeight)) {
                continue;
            }

            if (throughputValue != null &&
                    !looksLikeServerCapacityLine(primaryText)) {
                double score = visualScore(line.bounds, imageWidth, imageHeight,
                    role == Role.DOWNLOAD || role == Role.UPLOAD, normalized);
                throughput.add(new Candidate(throughputValue.value, primaryText,
                    line.bounds,
                    role == Role.DOWNLOAD || role == Role.UPLOAD ? role : null,
                    score));
            }

            if (!latencyValue.isEmpty() && !containsAny(normalized, "jitter")) {
                double score = visualScore(line.bounds, imageWidth, imageHeight,
                    role == Role.LATENCY, normalized);
                latency.add(new Candidate(latencyValue, primaryText, line.bounds,
                    role == Role.LATENCY ? role : null, score));
            }
        }

        deduplicate(throughput);
        deduplicate(latency);

        Candidate download = firstLabelled(throughput, Role.DOWNLOAD);
        Candidate upload = firstLabelled(throughput, Role.UPLOAD);

        if (download == null) {
            download = nearestToMarker(throughput, markers, Role.DOWNLOAD,
                null, imageWidth, imageHeight);
        }
        if (upload == null) {
            upload = nearestToMarker(throughput, markers, Role.UPLOAD,
                download, imageWidth, imageHeight);
        }

        if (download == null || upload == null || download == upload) {
            Candidate[] pair = choosePrimaryThroughputPair(
                throughput, imageWidth, imageHeight);
            if (download == null) download = pair[0];
            if (upload == null || upload == download) upload = pair[1];
        }

        Candidate ping = firstLabelled(latency, Role.LATENCY);
        if (ping == null) {
            ping = nearestToMarker(latency, markers, Role.LATENCY,
                null, imageWidth, imageHeight);
        }
        if (ping == null) {
            ping = choosePrimaryLatency(latency, download, upload,
                imageWidth, imageHeight);
        }

        if (download != null) result.download = download.value;
        if (upload != null && upload != download) result.upload = upload.value;
        if (ping != null) result.latency = ping.value;
        result.jitter = "";
        return result;
    }

    private static List<Line> expandSplitMetricLines(List<Line> source,
            int width, int height) {
        List<Line> expanded = new ArrayList<>(source);
        List<Rect> averageLabels = new ArrayList<>();
        for (Line line : source) {
            if (line != null && !line.bounds.isEmpty() &&
                    isAverageLine(normalize(line.text))) {
                averageLabels.add(new Rect(line.bounds));
            }
        }
        for (int i = 0; i < source.size(); i++) {
            Line numberLine = source.get(i);
            String number = numberOnly(numberLine == null ? "" : numberLine.text);
            if (number.isEmpty() || numberLine.bounds.isEmpty()) continue;

            for (int j = 0; j < source.size(); j++) {
                if (i == j) continue;
                Line unitLine = source.get(j);
                if (unitLine == null || unitLine.bounds.isEmpty()) continue;
                String unit = metricUnitOnly(unitLine.text);
                if (unit.isEmpty() ||
                        !nearbyMetricFragments(numberLine.bounds, unitLine.bounds,
                            width, height) ||
                        averageLabelBetween(numberLine.bounds, unitLine.bounds,
                            averageLabels)) {
                    continue;
                }
                Rect union = new Rect(numberLine.bounds);
                union.union(unitLine.bounds);
                expanded.add(new Line(number + " " + unit, union));
            }
        }
        return expanded;
    }

    private static String numberOnly(String source) {
        Matcher matcher = NUMBER_ONLY.matcher(source == null ? "" : source.trim());
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static String metricUnitOnly(String source) {
        String unit = normalize(source).replace(" ", "");
        if (unit.matches("(?:gbit/s|gb/s|gbps|mbit/s|mb/s|mbps|mbits/s|mbit|mbits)")) {
            return unit;
        }
        if (unit.matches("m(?:illi)?s(?:ec(?:ond)?s?)?")) return "ms";
        return "";
    }

    private static boolean averageLabelBetween(Rect first, Rect second,
            List<Rect> averages) {
        float minX = Math.min(first.exactCenterX(), second.exactCenterX());
        float maxX = Math.max(first.exactCenterX(), second.exactCenterX());
        float centerY = (first.exactCenterY() + second.exactCenterY()) / 2f;
        for (Rect average : averages) {
            float tolerance = Math.max(Math.max(first.height(), second.height()),
                average.height()) * 1.5f + 8f;
            if (Math.abs(average.exactCenterY() - centerY) <= tolerance &&
                    average.exactCenterX() > minX &&
                    average.exactCenterX() < maxX) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearbyMetricFragments(Rect number, Rect unit,
            int width, int height) {
        float dy = Math.abs(number.exactCenterY() - unit.exactCenterY());
        float dx = Math.abs(number.exactCenterX() - unit.exactCenterX());
        int horizontalGap = Math.max(0,
            Math.max(number.left, unit.left) - Math.min(number.right, unit.right));
        int verticalGap = Math.max(0,
            Math.max(number.top, unit.top) - Math.min(number.bottom, unit.bottom));
        boolean sameRow = dy <= Math.max(number.height(), unit.height()) * 1.35f + 10f &&
            horizontalGap <= Math.max(number.height(), unit.height()) * 2.0f + 12f;
        boolean stacked = dx <= Math.max(number.width(), unit.width()) * 0.60f + 12f &&
            verticalGap <= Math.max(number.height(), unit.height()) * 1.20f + 12f;
        return sameRow || stacked;
    }

    private static String stripAveragePortion(String source) {
        if (source == null || source.trim().isEmpty()) return "";
        Matcher matcher = AVERAGE_LABEL.matcher(source);
        return matcher.find() ? source.substring(0, matcher.start()).trim() : source.trim();
    }

    private static Role roleFor(String raw) {
        String normalized = normalize(raw);
        if (containsAny(normalized,
                "download", "descarga", "bajada", "debit descendant") ||
                containsDownArrow(raw)) {
            return Role.DOWNLOAD;
        }
        if (containsAny(normalized,
                "upload", "subida", "carga", "debit montant") ||
                containsUpArrow(raw)) {
            return Role.UPLOAD;
        }
        if (containsAny(normalized, "latency", "latencia", "ping")) {
            return Role.LATENCY;
        }
        return null;
    }

    private static boolean containsDownArrow(String value) {
        if (value == null) return false;
        return value.contains("↓") || value.contains("⬇") || value.contains("⇩") ||
            value.contains("▼") || value.contains("⭣") || value.contains("▾");
    }

    private static boolean containsUpArrow(String value) {
        if (value == null) return false;
        return value.contains("↑") || value.contains("⬆") || value.contains("⇧") ||
            value.contains("▲") || value.contains("⭡") || value.contains("▴");
    }

    private static boolean isAverageLine(String normalized) {
        return containsAny(normalized,
            "media", "average", "avg", "promedio", "moyenne");
    }

    private static boolean isDetachedAverageValue(Rect candidate,
            List<Rect> averageMarkers, int width, int height) {
        if (candidate == null || candidate.isEmpty() ||
                averageMarkers == null || averageMarkers.isEmpty()) {
            return false;
        }
        for (Rect average : averageMarkers) {
            if (average == null || average.isEmpty()) continue;
            float dx = Math.abs(candidate.exactCenterX() - average.exactCenterX());
            float dy = Math.abs(candidate.exactCenterY() - average.exactCenterY());
            float sameRowTolerance = Math.max(average.height(), candidate.height()) * 0.9f + 8f;
            boolean sameRow = dy <= sameRowTolerance &&
                candidate.left >= average.left - width * 0.08f &&
                candidate.left <= average.right + width * 0.62f;
            float stackedTolerance = Math.max(average.height(),
                candidate.height()) * 0.90f + 10f;
            boolean stacked = candidate.top >= average.bottom - 6 &&
                candidate.top <= average.bottom + stackedTolerance &&
                dx <= width * 0.38f;
            if (sameRow || stacked) return true;
        }
        return false;
    }

    private static boolean isUsefulLine(Line line, int width, int height) {
        if (line == null || line.text.isEmpty() || line.bounds.isEmpty()) return false;
        int centerY = line.bounds.centerY();
        int centerX = line.bounds.centerX();
        if (centerY < height * 0.10f || centerY > height * 0.86f) return false;
        if (centerX < width * 0.03f || centerX > width * 0.98f) return false;

        String normalized = normalize(line.text);
        return !containsAny(normalized,
            "aplicaciones para", "applications for", "descargue nuestras",
            "download our", "adsl", "vdsl", "test de velocidad para tu conexion",
            "speed test for your connection", "windows", "android") &&
            !looksLikeServerCapacityLine(line.text);
    }

    private static boolean looksLikeServerCapacityLine(String source) {
        String normalized = normalize(source);
        if (!containsAny(normalized, "gbit/s", "gb/s", "gbps")) return false;

        if (roleFor(source) == Role.DOWNLOAD || roleFor(source) == Role.UPLOAD) {
            return false;
        }

        String residue = normalized
            .replaceAll("[0-9]{1,5}(?:[.,][0-9]{1,3})?\\s*(?:gbit/s|gb/s|gbps)", " ")
            .replaceAll("[^a-z]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return residue.length() > 2;
    }

    private static double visualScore(Rect bounds, int width, int height,
            boolean labelled, String normalized) {
        double score = bounds.height() * 12.0d + bounds.width() * 0.10d;
        float cx = bounds.exactCenterX() / Math.max(1f, width);
        float cy = bounds.exactCenterY() / Math.max(1f, height);
        if (labelled) score += 700.0d;
        if (cx >= 0.28f && cx <= 0.96f) score += 100.0d;
        if (cy >= 0.25f && cy <= 0.74f) score += 160.0d;
        if (cy < 0.24f) score -= 180.0d;
        if (containsAny(normalized, "up to", "hasta", "maximum", "maximo")) {
            score -= 900.0d;
        }
        return score;
    }

    private static Candidate firstLabelled(List<Candidate> candidates, Role role) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.labelledRole != role) continue;
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best;
    }

    private static Candidate nearestToMarker(List<Candidate> candidates,
            List<Marker> markers, Role role, Candidate excluded,
            int width, int height) {
        Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Marker marker : markers) {
            if (marker.role != role) continue;
            for (Candidate candidate : candidates) {
                if (candidate == excluded || isAverageLine(normalize(candidate.raw))) continue;
                float dx = Math.abs(candidate.bounds.exactCenterX() -
                    marker.bounds.exactCenterX());
                float dy = Math.abs(candidate.bounds.exactCenterY() -
                    marker.bounds.exactCenterY());
                if (dx > width * 0.58f || dy > height * 0.18f) continue;
                double distance = dy / Math.max(1.0d, height) * 2.0d +
                    dx / Math.max(1.0d, width);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Candidate[] choosePrimaryThroughputPair(
            List<Candidate> candidates, int width, int height) {
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            float cy = candidate.bounds.exactCenterY() / Math.max(1f, height);
            float cx = candidate.bounds.exactCenterX() / Math.max(1f, width);
            if (cy < 0.25f || cy > 0.76f || cx < 0.18f) continue;
            if (looksLikeGaugeScale(candidate, candidates)) continue;
            eligible.add(candidate);
        }
        if (eligible.size() < 2) return new Candidate[]{null, null};

        Collections.sort(eligible, Comparator
            .comparingInt((Candidate candidate) -> candidate.bounds.centerY())
            .thenComparingInt(candidate -> candidate.bounds.centerX()));

        Candidate first = eligible.get(0);
        Candidate second = null;
        for (int i = 1; i < eligible.size(); i++) {
            Candidate candidate = eligible.get(i);
            if (!samePhysicalValue(first, candidate)) {
                second = candidate;
                break;
            }
        }
        if (second != null &&
                Math.abs(first.bounds.centerY() - second.bounds.centerY()) <=
                    height * 0.045f &&
                first.bounds.centerX() > second.bounds.centerX()) {
            Candidate swap = first;
            first = second;
            second = swap;
        }
        return new Candidate[]{first, second};
    }

    private static Candidate choosePrimaryLatency(List<Candidate> candidates,
            Candidate download, Candidate upload, int width, int height) {
        if (candidates.isEmpty()) return null;
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            float cy = candidate.bounds.exactCenterY() / Math.max(1f, height);
            float cx = candidate.bounds.exactCenterX() / Math.max(1f, width);
            if (cy < 0.24f || cy > 0.78f || cx < 0.12f) continue;
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) return null;

        Collections.sort(eligible, Comparator.comparingInt(
            candidate -> candidate.bounds.centerY()));
        if (upload != null) {
            Candidate belowUpload = null;
            for (Candidate candidate : eligible) {
                if (candidate.bounds.centerY() >= upload.bounds.centerY() - height * 0.04f) {
                    if (belowUpload == null || candidate.bounds.centerY() < belowUpload.bounds.centerY()) {
                        belowUpload = candidate;
                    }
                }
            }
            if (belowUpload != null) return belowUpload;
        }

        Candidate best = eligible.get(0);
        for (Candidate candidate : eligible) {
            if (Math.abs(candidate.bounds.centerY() - best.bounds.centerY()) <=
                    height * 0.06f && candidate.bounds.centerX() > best.bounds.centerX()) {
                best = candidate;
            } else if (candidate.score > best.score + 250.0d) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean looksLikeGaugeScale(Candidate candidate,
            List<Candidate> all) {
        if (looksLikeServerCapacityLine(candidate.raw)) return true;
        double value = numeric(candidate.value);
        if (value < 900.0d || !normalize(candidate.raw).contains("g")) return false;
        int largerCandidates = 0;
        for (Candidate other : all) {
            if (other == candidate) continue;
            if (other.bounds.height() >= candidate.bounds.height() * 1.25f) {
                largerCandidates++;
            }
        }
        return largerCandidates >= 2;
    }

    private static void deduplicate(List<Candidate> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Candidate current = candidates.get(i);
            for (int j = 0; j < i; j++) {
                Candidate earlier = candidates.get(j);
                if (samePhysicalValue(current, earlier)) {
                    if (current.score > earlier.score) candidates.set(j, current);
                    candidates.remove(i);
                    break;
                }
            }
        }
    }

    private static boolean samePhysicalValue(Candidate left, Candidate right) {
        return left.value.equals(right.value) &&
            Math.abs(left.bounds.centerX() - right.bounds.centerX()) < 28 &&
            Math.abs(left.bounds.centerY() - right.bounds.centerY()) < 28;
    }

    private static final class MetricValue {
        final String value;
        MetricValue(String value) { this.value = value; }
    }

    private static MetricValue parseThroughput(String source) {
        if (isAverageLine(normalize(source))) return null;
        Matcher valueUnit = THROUGHPUT_VALUE_UNIT.matcher(source);
        if (valueUnit.find()) {
            String value = throughputMbps(valueUnit.group(1), valueUnit.group(2));
            return value.isEmpty() ? null : new MetricValue(value);
        }
        Matcher unitValue = THROUGHPUT_UNIT_VALUE.matcher(source);
        if (unitValue.find()) {
            String value = throughputMbps(unitValue.group(2), unitValue.group(1));
            return value.isEmpty() ? null : new MetricValue(value);
        }
        return null;
    }

    private static String parseLatency(String source) {
        if (isAverageLine(normalize(source))) return "";
        Matcher valueUnit = LATENCY_VALUE_UNIT.matcher(source);
        if (valueUnit.find()) return decimal(valueUnit.group(1));
        Matcher unitValue = LATENCY_UNIT_VALUE.matcher(source);
        return unitValue.find() ? decimal(unitValue.group(1)) : "";
    }

    private static String throughputMbps(String numeric, String unit) {
        try {
            double value = Double.parseDouble(numeric.replace(',', '.'));
            if (normalize(unit).startsWith("g")) value *= 1000.0d;
            return value > 0.0d && value < 100000.0d ? decimal(value) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static double numeric(String value) {
        try {
            return Double.parseDouble(value == null ? "" : value.replace(',', '.'));
        } catch (Exception ignored) {
            return -1.0d;
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
