package com.netlife.speedtestnl;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Persists a completed combined result before attempting its Drive upload.
 *
 * A failed or ambiguous network request never discards the TXT. The pending
 * file remains in app-private storage and can be uploaded again without
 * repeating either speed test.
 */
final class ResultSaveManager {

    interface Callback {
        void onStatus(String message);
        void onSuccess(File localFile);
        void onFailure(String detail, File localFile);
    }

    private static final String PENDING_DIR = "pending_results";
    private static final int MAX_UPLOAD_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MS = {0L, 5000L, 15000L};

    private ResultSaveManager() { }

    static void persistAndUpload(Context context, String endpoint,
            String fileName, String content, Callback callback) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            File pendingFile;
            try {
                pendingFile = persistAtomically(appContext, fileName, content);
            } catch (Exception error) {
                callback.onFailure("No se pudo crear la copia local: " +
                    safeMessage(error), null);
                return;
            }

            UploadOutcome last = null;
            for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
                if (RETRY_DELAYS_MS[attempt - 1] > 0L) {
                    callback.onStatus("Reintentando Google Drive (" + attempt + "/" +
                        MAX_UPLOAD_ATTEMPTS + ")...");
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[attempt - 1]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        callback.onFailure("La subida fue interrumpida", pendingFile);
                        return;
                    }
                } else {
                    callback.onStatus("Subiendo resultado a Google Drive...");
                }

                last = upload(endpoint, fileName, content);
                if (last.success) {
                    // The local file is only a pending queue item. Remove it
                    // after the remote endpoint explicitly confirms success.
                    if (pendingFile.exists() && !pendingFile.delete()) {
                        pendingFile.deleteOnExit();
                    }
                    callback.onSuccess(pendingFile);
                    return;
                }
            }

            String detail = last == null ? "Error de subida no especificado" : last.detail;
            callback.onFailure(detail, pendingFile);
        }, "SpeedtestNL-ResultSave").start();
    }

    private static File persistAtomically(Context context, String fileName,
            String content) throws Exception {
        File directory = new File(context.getFilesDir(), PENDING_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("No se pudo crear " + directory.getAbsolutePath());
        }

        File target = new File(directory, sanitizeFileName(fileName));
        File temporary = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("No se pudo reemplazar el TXT pendiente");
        }
        if (!temporary.renameTo(target)) {
            copyFile(temporary, target);
            if (!temporary.delete()) temporary.deleteOnExit();
        }
        return target;
    }

    private static void copyFile(File source, File target) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static UploadOutcome upload(String endpoint, String fileName,
            String content) {
        HttpURLConnection connection = null;
        try {
            JSONObject request = new JSONObject();
            request.put("fileName", fileName);
            request.put("content", content);
            request.put("requestId", fileName);

            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
            String response = readResponse(stream);
            boolean confirmed = status >= 200 && status < 300 &&
                isConfirmedSuccess(response);
            if (confirmed) return new UploadOutcome(true, "OK");

            String responseDetail = response.isEmpty() ? "sin respuesta" : abbreviate(response);
            return new UploadOutcome(false,
                "Drive respondió HTTP " + status + ": " + responseDetail);
        } catch (Exception error) {
            return new UploadOutcome(false,
                "Error de conexión con Drive: " + safeMessage(error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isConfirmedSuccess(String response) {
        if (response == null) return false;
        String trimmed = response.trim();
        if (trimmed.isEmpty()) return false;
        try {
            JSONObject json = new JSONObject(trimmed);
            if (json.optBoolean("ok", false) || json.optBoolean("success", false)) {
                return true;
            }
            String status = json.optString("status", "").trim().toLowerCase(Locale.ROOT);
            String result = json.optString("result", "").trim().toLowerCase(Locale.ROOT);
            return status.equals("ok") || status.equals("success") ||
                result.equals("ok") || result.equals("success");
        } catch (Exception ignored) {
            String normalized = trimmed.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
            return normalized.equals("ok") || normalized.equals("success") ||
                normalized.startsWith("ok:") || normalized.startsWith("success:");
        }
    }

    private static String readResponse(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString().trim();
    }

    private static String sanitizeFileName(String fileName) {
        String safe = fileName == null ? "speedtest_result.txt" : fileName.trim();
        safe = safe.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "speedtest_result.txt" : safe;
    }

    private static String abbreviate(String value) {
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "...";
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "error desconocido";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message.trim();
    }

    private static final class UploadOutcome {
        final boolean success;
        final String detail;

        UploadOutcome(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }
}
