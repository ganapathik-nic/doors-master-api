package org.gepnic.doors.masterapi.aspect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Useful request diagnostics without creating a second credential store. */
public final class AuditDiagnostics {
    public static final String BODY_ATTRIBUTE = "X-DOORS-AUDIT-PLAINTEXT-BODY";
    public static final String CLIENT_NAME_ATTRIBUTE = "X-DOORS-AUDIT-CLIENT-NAME";
    public static final String CLIENT_ID_ATTRIBUTE = "X-DOORS-AUDIT-CLIENT-ID";
    public static final String API_KEY_FINGERPRINT_ATTRIBUTE = "X-DOORS-AUDIT-API-KEY-FINGERPRINT";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "x-api-key", "password", "passphrase", "secret", "token",
            "signature", "encryptedkey", "encrypted_key", "securedata", "secure_data", "privatekey", "private_key");

    private AuditDiagnostics() {}

    public static String apiKeyFingerprint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "UNAVAILABLE";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return "SHA-256:" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to fingerprint API key", failure);
        }
    }

    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                clean.put(name, isSensitive(name) ? "***REDACTED***" : redact(item));
            });
            return clean;
        }
        if (value instanceof List<?> list) {
            List<Object> clean = new ArrayList<>(list.size());
            list.forEach(item -> clean.add(redact(item)));
            return clean;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        return SENSITIVE_KEYS.contains(key.toLowerCase().replaceAll("[^a-z0-9_-]", ""));
    }
}
