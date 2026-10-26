package org.gepnic.doors.masterapi.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuditDiagnosticsTest {
    @Test
    void fingerprintIdentifiesTheKeyWithoutPersistingIt() {
        String apiKey = "reusable-secret-api-key";
        String fingerprint = AuditDiagnostics.apiKeyFingerprint(apiKey);

        assertThat(fingerprint).startsWith("SHA-256:").hasSize(24);
        assertThat(fingerprint).doesNotContain(apiKey);
        assertThat(fingerprint).isEqualTo(AuditDiagnostics.apiKeyFingerprint(apiKey));
    }

    @Test
    void nestedCredentialsAreRedactedButBusinessParametersRemainReadable() {
        Object clean = AuditDiagnostics.redact(Map.of(
                "params", Map.of("p_startDate", "2026-10-15", "password", "do-not-log"),
                "agentIds", List.of("GePNIC-Kerala"),
                "signature", "ciphertext"));

        assertThat(clean.toString()).contains("p_startDate=2026-10-15", "GePNIC-Kerala", "***REDACTED***")
                .doesNotContain("do-not-log", "ciphertext");
    }

    @Test
    void curlUsesVerifiedPlaintextInsteadOfEncryptedControllerArgument() {
        AuditAspect aspect = new AuditAspect(mock(JdbcTemplate.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/master/gateway/orchestrate/KeralaWeeklyReport");
        request.setServerName("doorsapi.doors.test");
        request.addHeader("X-API-KEY", "reusable-secret-api-key");
        request.setAttribute(AuditDiagnostics.CLIENT_NAME_ATTRIBUTE, "Kerala application");
        request.setAttribute(AuditDiagnostics.CLIENT_ID_ATTRIBUTE, 42L);
        request.setAttribute(AuditDiagnostics.API_KEY_FINGERPRINT_ATTRIBUTE, "SHA-256:1234567890abcdef");
        Map<String, Object> plaintext = new LinkedHashMap<>();
        plaintext.put("agentIds", List.of("GePNIC-Kerala"));
        plaintext.put("params", Map.of("p_startDate", "2026-10-15", "p_endDate", "2026-10-21"));
        request.setAttribute(AuditDiagnostics.BODY_ATTRIBUTE, plaintext);

        String command = ReflectionTestUtils.invokeMethod(aspect, "generateCurl", request,
                Map.of("secureData", "encrypted-body", "signature", "encrypted-signature"));

        assertThat(command).contains("Kerala application", "ID: 42", "SHA-256:1234567890abcdef",
                        "p_startDate", "2026-10-15", "GePNIC-Kerala", "X-API-KEY: reusable-secret-api-key")
                .doesNotContain("encrypted-body", "encrypted-signature");
    }
}
