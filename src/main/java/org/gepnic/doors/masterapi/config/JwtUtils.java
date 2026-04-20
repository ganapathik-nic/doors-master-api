package org.gepnic.doors.masterapi.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtils {

    private SecretKey ephemeralKey; // 🛡️ Exists only in JVM RAM
    private static final long EXPIRATION_TIME = 8 * 60 * 60 * 1000; // 8 Hours

    @PostConstruct
    public void init() {
        try {
            // 🛡️ Generate a fresh AES-256 key every time the Master-API starts
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            this.ephemeralKey = keyGen.generateKey();
            System.out.println("🛡️ DOORS: Ephemeral JWE Key generated successfully.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Ephemeral Key", e);
        }
    }

    /**
     * GENERATE: Now creates an ENCRYPTED (JWE) token
     */
    public String generateToken(String username, String role, String sessionId) {
        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(username)
                    .claim("role", role)
                    .claim("sid", sessionId) // 🛡️ CRITICAL: Single Session ID
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                    .build();

            // Header for Direct Encryption using AES-GCM
            JWEHeader header = new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM);

            EncryptedJWT jwe = new EncryptedJWT(header, claimsSet);
            jwe.encrypt(new DirectEncrypter(this.ephemeralKey));

            return jwe.serialize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * DECRYPT & PARSE: Decodes the encrypted payload
     */
    public Map<String, Object> parseToken(String token) {
        try {
            EncryptedJWT jwe = EncryptedJWT.parse(token);
            jwe.decrypt(new DirectDecrypter(this.ephemeralKey));
            return jwe.getJWTClaimsSet().getClaims();
        } catch (Exception e) {
            // If Master-API restarted, old tokens will fail here (Key mismatch)
            return null;
        }
    }
}