package org.gepnic.doors.masterapi.controller;

import org.junit.jupiter.api.Test;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.*;
import org.gepnic.doors.masterapi.service.*;
import org.gepnic.doors.masterapi.exception.GlobalExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GatewayCryptoValidationTest {
    @Test void tamperedGcmReturnsGeneric400WithoutExecution() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        var pair=generator.generateKeyPair();
        var client=new ApiClient();client.setClientId(26L);client.setClientName("fixture");
        client.setClientPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var clients=mock(ApiClientRepository.class);
        when(clients.findByApiKey("test-key")).thenReturn(Optional.of(client));
        var storage=mock(CertificateStorageService.class);
        when(storage.getActiveSigningPrivateKey()).thenReturn(pair.getPrivate());
        var reports=mock(ReportViewerService.class);
        var controller=new ExternalGatewayController(reports,clients,storage,mock(DoorsSigningCertificateRepository.class),
                mock(TemplateContractService.class),mock(ClientSpecificDataSegregationService.class),mock(ApiClientExecutionPolicy.class));
        var protocol=mock(GatewayProtocolService.class);
        when(protocol.requireAllowed(26L,"test",1)).thenReturn("LEGACY");
        ReflectionTestUtils.setField(controller,"gatewayProtocol",protocol);
        byte[] key=new byte[32], iv=new byte[12];new SecureRandom().nextBytes(key);new SecureRandom().nextBytes(iv);
        var aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        byte[] ciphertext=aes.doFinal("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));ciphertext[0]^=1;
        var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,pair.getPublic());
        var encoder=Base64.getEncoder();
        var body=Map.of("encryptedKey",encoder.encodeToString(rsa.doFinal(key)),"iv",encoder.encodeToString(iv),
                "secureData",encoder.encodeToString(ciphertext),"signature","AA==");
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/master/gateway/orchestrate/test").header("X-API-KEY","test-key")
                .contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOORS-CRYPTO-INVALID"));
        verifyNoInteractions(reports);
        verify(protocol,never()).validateAndClaim(anyLong(),anyString(),anyInt(),anyBoolean(),anyMap());
    }
}
