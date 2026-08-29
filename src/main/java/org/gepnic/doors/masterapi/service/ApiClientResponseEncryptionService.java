package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.exception.EncryptionException;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ApiClientResponseEncryptionService {

    private final ApiClientRepository apiClientRepository;
    private final CertificateStorageService certificateStorageService;
    private final DoorsSigningCertificateRepository certRepository;
    private final ObjectMapper objectMapper;

    public EncryptedResponse encrypt(String apiKey, Object response) {
        ApiClient client = apiClientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        if (client.getClientPublicKey() == null || client.getClientPublicKey().isBlank()) {
            throw new EncryptionException("API Client public key is not configured");
        }

        try {
            DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                    .orElseThrow(() -> new IllegalStateException(
                            "Active database signature token registration configuration missing"));
            PrivateKey signingKey = certificateStorageService.getActiveSigningPrivateKey();
            if (signingKey == null) throw new IllegalStateException("Active DOORS signing private key is unavailable");

            String plaintext = objectMapper.writeValueAsString(response);
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(signingKey);
            signer.update(plaintext.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());

            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            SecretKey aesKey = keyGenerator.generateKey();
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] ciphertext = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, extractPublicKey(client.getClientPublicKey()));
            byte[] encryptedKey = rsaCipher.doFinal(aesKey.getEncoded());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("encryptedKey", Base64.getEncoder().encodeToString(encryptedKey));
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("secureData", Base64.getEncoder().encodeToString(ciphertext));
            envelope.put("signature", signature);
            return new EncryptedResponse(envelope, activeCert.getKeyId());
        } catch (EncryptionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EncryptionException("Document response encryption failed: " + exception.getMessage(), exception);
        }
    }

    private PublicKey extractPublicKey(String rawKey) throws Exception {
        String cleanKey = rawKey.trim();
        if (cleanKey.contains("BEGIN CERTIFICATE")) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream input = new ByteArrayInputStream(cleanKey.getBytes(StandardCharsets.UTF_8))) {
                return ((X509Certificate) factory.generateCertificate(input)).getPublicKey();
            }
        }
        String base64 = cleanKey.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
        byte[] bytes = Base64.getDecoder().decode(base64);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception ignored) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                return ((X509Certificate) factory.generateCertificate(input)).getPublicKey();
            }
        }
    }

    public record EncryptedResponse(Map<String, Object> envelope, String keyId) { }
}
