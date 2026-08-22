package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TotpServiceTest {

    private final TotpService service = new TotpService(
            "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=", false);

    @Test
    void calculatesRfc6238CompatibleSixDigitCode() {
        assertEquals(287082, service.codeForCounter("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1));
    }

    @Test
    void encryptsMfaSecretsWithRandomizedAuthenticatedEncryption() {
        String secret = "JBSWY3DPEHPK3PXP";
        String first = service.encryptSecret(secret);
        String second = service.encryptSecret(secret);
        assertNotEquals(first, second);
        assertEquals(secret, service.decryptSecret(first));
        assertEquals(secret, service.decryptSecret(second));
    }
}
