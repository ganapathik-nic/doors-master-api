package org.gepnic.doors.masterapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("Requires a deterministic API-client test fixture")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExternalGatewayIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair clientKeyPair;
    private String publicKeyBase64;
    private final String mockApiKey = "DEV_MOCK_SECRET_KEY_123456";

    @BeforeEach
    void setup() throws Exception {
        // Simulating Client RAM Key Generation
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        this.clientKeyPair = keyGen.generateKeyPair();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(clientKeyPair.getPublic().getEncoded());
    }

    @Test
    void testEndToEndSecureOrchestrationFlow() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", mockApiKey);

        // 1. EXECUTE STEP 1: Handshake
        Map<String, String> handshakePayload = Map.of("publicKey", this.publicKeyBase64);
        HttpEntity<Map<String, String>> handshakeRequest = new HttpEntity<>(handshakePayload, headers);
        
        ResponseEntity<String> handshakeResponse = restTemplate.postForEntity(
                "/api/v1/master/gateway/handshake", handshakeRequest, String.class);
        
        assertEquals(HttpStatus.OK, handshakeResponse.getStatusCode());

        // 2. EXECUTE STEP 2: Orchestration Query Extraction
        Map<String, Object> queryPayload = Map.of("agentIds", List.of("ALL"), "params", Map.of());
        HttpEntity<Map<String, Object>> orchestrationRequest = new HttpEntity<>(queryPayload, headers);
        
        ResponseEntity<Map> orchestrationResponse = restTemplate.postForEntity(
                "/api/v1/master/gateway/orchestrate/dev_summary_query", orchestrationRequest, Map.class);

        assertEquals(HttpStatus.OK, orchestrationResponse.getStatusCode());
        Map<String, String> body = orchestrationResponse.getBody();
        assertNotNull(body);

        // 3. VERIFY AND DECRYPT THE DATA SHARING PAYLOAD
        String encryptedKeyBase64 = body.get("encryptedKey");
        String ivBase64 = body.get("iv");
        String secureDataBase64 = body.get("secureData");

        // A. Decrypt the Dynamic AES key using the client's Private Key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, clientKeyPair.getPrivate());
        byte[] rawAesKey = rsaCipher.doFinal(Base64.getDecoder().decode(encryptedKeyBase64));

        // B. Decrypt the actual reporting payload using the opened AES key
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(rawAesKey, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(Base64.getDecoder().decode(ivBase64));
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        
        byte[] decryptedDataBytes = aesCipher.doFinal(Base64.getDecoder().decode(secureDataBase64));
        String plainTextJson = new String(decryptedDataBytes, "UTF-8");

        System.out.println("🚀 DECRYPTED DEV PAYLOAD DISCOVERED:\n" + plainTextJson);
        assertTrue(plainTextJson.contains("success") || plainTextJson.contains("data"));
    }
}
