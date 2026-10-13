package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentSignatureTest {
    @Test void signedMetadataVerifiesAndRejectsTamperingAndWrongKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        var certificates = mock(DoorsSigningCertificateRepository.class);
        var storage = mock(CertificateStorageService.class);
        var cert = new DoorsSigningCertificate();
        cert.setKeyId("test-key");
        when(certificates.findByIsActiveTrue()).thenReturn(Optional.of(cert));
        when(storage.getSigningPrivateKey("test-key")).thenReturn(pair.getPrivate());
        var service = new ApiClientResponseEncryptionService(mock(ApiClientRepository.class), storage, certificates, new ObjectMapper());
        var signed = service.signPayload(Map.of("downloadId", "123", "sha256", "abc", "size", 3));
        byte[] payload = Base64.getDecoder().decode(signed.payload());
        byte[] signature = Base64.getDecoder().decode(signed.signature());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic()); verifier.update(payload);
        assertThat(verifier.verify(signature)).isTrue();
        payload[0] ^= 1;
        verifier.initVerify(pair.getPublic()); verifier.update(payload);
        assertThat(verifier.verify(signature)).isFalse();
        verifier.initVerify(generator.generateKeyPair().getPublic());
        verifier.update(Base64.getDecoder().decode(signed.payload()));
        assertThat(verifier.verify(signature)).isFalse();
        assertThat(signed.keyId()).isEqualTo("test-key");
        verify(storage).getSigningPrivateKey("test-key");
    }

    @Test void missingSigningIdentityFailsClosed() {
        var certificates = mock(DoorsSigningCertificateRepository.class);
        when(certificates.findByIsActiveTrue()).thenReturn(Optional.empty());
        var service = new ApiClientResponseEncryptionService(mock(ApiClientRepository.class), mock(CertificateStorageService.class), certificates, new ObjectMapper());
        assertThatThrownBy(() -> service.signPayload(Map.of("sha256", "abc")))
                .hasMessageContaining("Document signature failed");
    }
}
