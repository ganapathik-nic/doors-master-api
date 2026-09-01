package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class CurlDocumentDownloader implements DocumentDownloader {

    private static final String WRITE_OUT = "%{http_code}\\n%{content_type}";
    private final String executable;
    private final String authorizationSecret;
    private final String authorizationClientId;

    public CurlDocumentDownloader(
            @Value("${doors.documents.curl-executable:curl}") String executable,
            @Value("${doors.documents.authorization-secret:SecretKeyForReports}") String authorizationSecret,
            @Value("${doors.documents.authorization-client-id:101}") String authorizationClientId) {
        this.executable = executable;
        this.authorizationSecret = authorizationSecret;
        this.authorizationClientId = authorizationClientId;
    }

    @Override
    public DownloadResponse download(URI uri, DocumentServiceRegistration registration) {
        Path bodyFile = null;
        Path metadataFile = null;
        Path errorFile = null;
        try {
            bodyFile = Files.createTempFile("doors-document-", ".download");
            metadataFile = Files.createTempFile("doors-curl-", ".metadata");
            errorFile = Files.createTempFile("doors-curl-", ".error");

            int connectTimeoutSeconds = seconds(registration.getConnectTimeoutMs(), 10_000);
            int readTimeoutSeconds = seconds(registration.getReadTimeoutMs(), 120_000);
            List<String> command = new ArrayList<>(List.of(
                    executable,
                    "--silent",
                    "--show-error",
                    "--location",
                    "--fail-with-body",
                    "--user-agent", "curl/8.0.0",
                    "--header", "Authorization: " + buildAuthorization(uri),
                    "--connect-timeout", Integer.toString(connectTimeoutSeconds),
                    "--max-time", Integer.toString(readTimeoutSeconds),
                    "--output", bodyFile.toString(),
                    "--write-out", WRITE_OUT));
            if (Boolean.FALSE.equals(registration.getVerifyTls())) {
                command.add("--insecure");
            }
            command.add(uri.toASCIIString());

            Process process = new ProcessBuilder(command)
                    .redirectOutput(metadataFile.toFile())
                    .redirectError(errorFile.toFile())
                    .start();
            boolean finished = process.waitFor(readTimeoutSeconds + 5L, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("curl document download timed out");
            }

            String metadata = Files.readString(metadataFile, StandardCharsets.UTF_8).trim();
            String error = Files.readString(errorFile, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("curl document download failed (exit "
                        + process.exitValue() + "): " + safeError(error));
            }

            String[] values = metadata.split("\\R", 2);
            int status = values.length == 0 || values[0].isBlank() ? 0 : Integer.parseInt(values[0].trim());
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Document service returned HTTP " + status);
            }
            MediaType contentType = values.length < 2 || values[1].isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(values[1].trim());
            long contentLength = Files.size(bodyFile);
            String sha256 = digest(bodyFile);
            Path completedBody = bodyFile;
            bodyFile = null; // ownership is transferred to DownloadResponse
            return new DownloadResponse(completedBody, contentLength, sha256, contentType, status);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to execute curl for document download", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("curl document download was interrupted", e);
        } finally {
            deleteQuietly(bodyFile);
            deleteQuietly(metadataFile);
            deleteQuietly(errorFile);
        }
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static int seconds(Integer milliseconds, int fallback) {
        return Math.max(1, (int) Math.ceil((milliseconds == null ? fallback : milliseconds) / 1000.0));
    }

    String buildAuthorization(URI uri) {
        String downloadId = queryValue(uri, "downloadId");
        String docCode = queryValue(uri, "docCode");
        String fileName = queryValue(uri, "fileName");
        String packetType = queryValue(uri, "packetType");
        String value = downloadId + "|" + docCode + "|" + fileName + "|" + packetType
                + "|" + authorizationSecret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest) + "#" + authorizationClientId;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is unavailable", e);
        }
    }

    private static String queryValue(URI uri, String name) {
        return UriQuery.parse(uri.getRawQuery()).value(name);
    }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) return "no error details returned";
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are also cleaned by the host's temp-file policy.
        }
    }

    private record UriQuery(java.util.Map<String, String> values) {
        private static UriQuery parse(String rawQuery) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String pair : rawQuery.split("&")) {
                    String[] parts = pair.split("=", 2);
                    values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
                }
            }
            return new UriQuery(values);
        }

        private String value(String name) {
            return values.getOrDefault(name, "");
        }

        private static String decode(String value) {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

}
