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
 * Parses the visual nPerf result panel from ML Kit OCR lines.
 *
 * Chrome does not always expose the canvas result values through the
 * accessibility tree. This parser therefore uses the text bounding boxes to
 * reject browser chrome, advertisements and the small 1 Gb/s gauge scale, then
 * selects the large result values shown in the central result card.
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

    private static final Pattern THROUGHPUT_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]{1,4}(?:[.,][0-9]{1,3})?)\\s*" +
        "(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps|mbits/s|mbit\\s*/\\s*s)");
    private static final Pattern THROUGHPUT_UNIT_VALUE = Pattern.compile(
        "(?i)(gbit/s|gb/s|gbps|mbit/s|mb/s|mbps|mbits/s|mbit\\s*/\\s*s)" +
        "\\s*[:=-]?\\s*([0-9]{1,4}(?:[.,][0-9]{1,3})?)");
    private static final Pattern LATENCY_VALUE_UNIT = Pattern.compile(
        "(?i)([0-9]{1,4}(?:[.,][0-9]{1,3})?)\\s*m(?:illi)?s(?:ec(?:ond)?s?)?");
    private static final Pattern LATENCY_UNIT_VALUE = Pattern.compile(
        "(?i)m(?:illi)?s(?:ec(?:ond)?s?)?\\s*[:=-]?\\s*" +
        "([0-9]{1,4}(?:[.,][0-9]{1,3})?)");

    private static final class Candidate {
        final String value;
        final String raw;
        final Rect bounds;
        final boolean downloadLabel;
        final boolean uploadLabel;
        final boolean latencyLabel;
        final boolean jitterLabel;
        final double score;

        Candidate(String value, String raw, Rect bounds,
                boolean downloadLabel, boolean uploadLabel,
                boolean latencyLabel, boolean jitterLabel, double score) {
            this.value = value;
            this.raw = raw;
            this.bounds = new Rect(bounds);
            this.downloadLabel = downloadLabel;
            this.uploadLabel = uploadLabel;
            this.latencyLabel = latencyLabel;
            this.jitterLabel = jitterLabel;
            this.score = score;
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

        List<Candidate> throughput = new ArrayList<>();
        List<Candidate> latency = new ArrayList<>();

        for (Line line : lines) {
            if (!isUsefulLine(line, imageWidth, imageHeight)) continue;
            String normalized = normalize(line.text);
            boolean downloadLabel = containsAny(normalized,
                "download", "descarga", "bajada", "debit descendant");
            boolean uploadLabel = containsAny(normalized,
                "upload", "subida", "carga", "debit montant");
            boolean latencyLabel = containsAny(normalized,
                "latency", "latencia", "ping");
            boolean jitterLabel = normalized.contains("jitter");

            MetricValue throughputValue = parseThroughput(line.text);
            if (throughputValue != null) {
                double score = visualScore(line.bounds, imageWidth, imageHeight,
                    downloadLabel || uploadLabel, normalized);
                throughput.add(new Candidate(throughputValue.value, line.text,
                    line.bounds, downloadLabel, uploadLabel, false, false, score));
            }

            String latencyValue = parseLatency(line.text);
            if (!latencyValue.isEmpty()) {
                double score = visualScore(line.bounds, imageWidth, imageHeight,
                    latencyLabel || jitterLabel, normalized);
                latency.add(new Candidate(latencyValue, line.text, line.bounds,
                    false, false, latencyLabel, jitterLabel, score));
            }
        }

        deduplicate(throughput);
        deduplicate(latency);
        Collections.sort(throughput, candidateOrder());
        Collections.sort(latency, candidateOrder());

        Candidate download = firstLabelled(throughput, true);
        Candidate upload = firstLabelled(throughput, false);

        if (download == null || upload == null || download == upload) {
            Candidate[] visualPair = chooseVisualThroughputPair(
                throughput, imageWidth, imageHeight);
            if (download == null) download = visualPair[0];
            if (upload == null || upload == download) upload = visualPair[1];
        }

        if (download != null) result.download = download.value;
        if (upload != null) result.upload = upload.value;

        Candidate latencyCandidate = firstLatency(latency, false);
        Candidate jitterCandidate = firstLatency(latency, true);
        if (latencyCandidate == null) {
            latencyCandidate = chooseVisualLatency(latency, download, upload,
                imageHeight, false);
        }
        if (jitterCandidate == null) {
            jitterCandidate = chooseVisualLatency(latency, download, upload,
                imageHeight, true);
        }

        if (latencyCandidate != null) result.latency = latencyCandidate.value;
        if (jitterCandidate != null && jitterCandidate != latencyCandidate) {
            result.jitter = jitterCandidate.value;
        }
        return result;
    }

    private static boolean isUsefulLine(Line line, int width, int height) {
        if (line.text.isEmpty() || line.bounds.isEmpty()) return false;
        int centerY = line.bounds.centerY();
        int centerX = line.bounds.centerX();
        if (centerY < height * 0.11f || centerY > height * 0.84f) return false;
        if (centerX < width * 0.05f || centerX > width * 0.97f) return false;

        String normalized = normalize(line.text);
        return !containsAny(normalized,
            "aplicaciones para", "applications for", "descargue nuestras",
            "download our", "adsl", "vdsl", "test de velocidad para tu conexion",
            "speed test for your connection", "windows", "android");
    }

    private static double visualScore(Rect bounds, int width, int height,
            boolean labelled, String normalized) {
        double score = bounds.height() * 12.0d + bounds.width() * 0.12d;
        float cx = bounds.exactCenterX() / Math.max(1f, width);
        float cy = bounds.exactCenterY() / Math.max(1f, height);
        if (labelled) score += 500.0d;
        if (cx >= 0.35f && cx <= 0.92f) score += 90.0d;
        if (cy >= 0.28f && cy <= 0.72f) score += 130.0d;
        if (cy < 0.30f) score -= 120.0d;
        if (containsAny(normalized, "up to", "hasta", "maximum", "maximo")) {
            score -= 600.0d;
        }
        return score;
    }

    private static Comparator<Candidate> candidateOrder() {
        return (left, right) -> {
            int score = Double.compare(right.score, left.score);
            if (score != 0) return score;
            return Integer.compare(right.bounds.height(), left.bounds.height());
        };
    }

    private static Candidate firstLabelled(List<Candidate> candidates,
            boolean download) {
        for (Candidate candidate : candidates) {
            if (download && candidate.downloadLabel) return candidate;
            if (!download && candidate.uploadLabel) return candidate;
        }
        return null;
    }

    private static Candidate[] chooseVisualThroughputPair(
            List<Candidate> candidates, int width, int height) {
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            float cy = candidate.bounds.exactCenterY() / Math.max(1f, height);
            float cx = candidate.bounds.exactCenterX() / Math.max(1f, width);
            // The small gauge scale lives above the result card. Final values
            // are rendered in the central/lower result panel.
            if (cy < 0.30f || cy > 0.74f || cx < 0.28f) continue;
            if (looksLikeGaugeScale(candidate, candidates)) continue;
            eligible.add(candidate);
            if (eligible.size() >= 8) break;
        }
        if (eligible.size() < 2) return new Candidate[]{null, null};

        Candidate first = null;
        Candidate second = null;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < eligible.size(); i++) {
            for (int j = i + 1; j < eligible.size(); j++) {
                Candidate a = eligible.get(i);
                Candidate b = eligible.get(j);
                float dy = Math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY());
                float dx = Math.abs(a.bounds.exactCenterX() - b.bounds.exactCenterX());
                double pairScore = a.score + b.score;
                if (dy <= height * 0.22f) pairScore += 180.0d;
                if (dx <= width * 0.45f) pairScore += 80.0d;
                pairScore -= Math.abs(a.bounds.height() - b.bounds.height()) * 8.0d;
                if (pairScore > best) {
                    best = pairScore;
                    first = a;
                    second = b;
                }
            }
        }
        if (first == null || second == null) return new Candidate[]{null, null};

        float dy = Math.abs(first.bounds.exactCenterY() - second.bounds.exactCenterY());
        if (dy <= height * 0.045f) {
            return first.bounds.centerX() <= second.bounds.centerX()
                ? new Candidate[]{first, second} : new Candidate[]{second, first};
        }
        return first.bounds.centerY() <= second.bounds.centerY()
            ? new Candidate[]{first, second} : new Candidate[]{second, first};
    }

    private static boolean looksLikeGaugeScale(Candidate candidate,
            List<Candidate> all) {
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

    private static Candidate firstLatency(List<Candidate> candidates,
            boolean jitter) {
        for (Candidate candidate : candidates) {
            if (jitter && candidate.jitterLabel) return candidate;
            if (!jitter && candidate.latencyLabel && !candidate.jitterLabel) {
                return candidate;
            }
        }
        return null;
    }

    private static Candidate chooseVisualLatency(List<Candidate> candidates,
            Candidate download, Candidate upload, int height, boolean jitter) {
        if (candidates.isEmpty()) return null;
        float minY = 0f;
        if (download != null) minY = Math.max(minY, download.bounds.exactCenterY());
        if (upload != null) minY = Math.max(minY, upload.bounds.exactCenterY());

        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            float cy = candidate.bounds.exactCenterY();
            float normalizedY = cy / Math.max(1f, height);
            if (normalizedY < 0.30f || normalizedY > 0.76f) continue;
            if (minY > 0f && cy < minY - height * 0.06f) continue;
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) return null;
        Collections.sort(eligible, candidateOrder());
        if (!jitter) return eligible.get(0);
        return eligible.size() > 1 ? eligible.get(1) : null;
    }

    private static void deduplicate(List<Candidate> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Candidate current = candidates.get(i);
            for (int j = 0; j < i; j++) {
                Candidate earlier = candidates.get(j);
                boolean sameValue = current.value.equals(earlier.value);
                boolean near = Math.abs(current.bounds.centerX() - earlier.bounds.centerX()) < 24 &&
                    Math.abs(current.bounds.centerY() - earlier.bounds.centerY()) < 24;
                if (sameValue && near) {
                    if (current.score > earlier.score) candidates.set(j, current);
                    candidates.remove(i);
                    break;
                }
            }
        }
    }

    private static final class MetricValue {
        final String value;

        MetricValue(String value) {
            this.value = value;
        }
    }

    private static MetricValue parseThroughput(String source) {
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
