package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class ConfigurationAuditServiceTest {
    private final ConfigurationAuditService service =
            new ConfigurationAuditService(new JdbcTemplate(), new ObjectMapper());

    @Test
    void removesSecretsAndFingerprintsPublicKeys() {
        var sanitized = service.sanitize(Map.of(
                "allowedIps", "10.0.0.1",
                "apiKey", "client-api-key",
                "password", "secret-value",
                "targetDbPassword", "db-secret",
                "clientPublicKey", "-----BEGIN PUBLIC KEY-----abc"));

        assertThat(sanitized.get("allowedIps").asText()).isEqualTo("10.0.0.1");
        assertThat(sanitized.get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("apiKey").asText()).startsWith("SHA-256:");
        assertThat(sanitized.get("targetDbPassword").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("clientPublicKey").asText()).startsWith("SHA-256:");
        assertThat(sanitized.toString()).doesNotContain("secret-value", "db-secret", "BEGIN PUBLIC KEY");
    }
}
