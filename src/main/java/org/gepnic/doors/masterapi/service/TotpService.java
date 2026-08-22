package org.gepnic.doors.masterapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Service
public class TotpService {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec encryptionKey;

    public TotpService(
            @Value("${doors.security.mfa-encryption-key}") String configuredKey,
            @Value("${doors.security.manager-plane-enforced:false}") boolean managerPlaneEnforced) {
        if (managerPlaneEnforced
                && "REVWRUxPUE1FTlQtT05MWS0zMi1CWVRFLUtFWSEhISE=".equals(configuredKey)) {
            throw new IllegalStateException("Production manager plane cannot use the development MFA encryption key");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("doors.security.mfa-encryption-key must be Base64", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("doors.security.mfa-encryption-key must decode to exactly 32 bytes");
        }
        encryptionKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String generateSecret() {
        byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public boolean verify(String base32Secret, String submittedCode) {
        if (submittedCode == null || !submittedCode.matches("\\d{6}")) return false;
        long counter = System.currentTimeMillis() / 30_000L;
        int expected = Integer.parseInt(submittedCode);
        for (long offset = -1; offset <= 1; offset++) {
            if (codeForCounter(base32Secret, counter + offset) == expected) return true;
        }
        return false;
    }

    public String encryptSecret(String secret) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            ByteBuffer envelope = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(envelope.array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect MFA secret", exception);
        }
    }

    public String decryptSecret(String encryptedSecret) {
        try {
            byte[] envelope = Base64.getDecoder().decode(encryptedSecret);
            byte[] iv = java.util.Arrays.copyOfRange(envelope, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, 12, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read MFA secret", exception);
        }
    }

    public String provisioningUri(String username, String secret) {
        String account = java.net.URLEncoder.encode("DOORS:" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + account + "?secret=" + secret + "&issuer=DOORS&digits=6&period=30";
    }

    int codeForCounter(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify MFA code", exception);
        }
    }

    private String base32Encode(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32[(buffer >> (bitsLeft - 5)) & 31]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) result.append(BASE32[(buffer << (5 - bitsLeft)) & 31]);
        return result.toString();
    }

    private byte[] base32Decode(String value) {
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char character : normalized.toCharArray()) {
            int index = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            if (index < 0) throw new IllegalArgumentException("Invalid Base32 MFA secret");
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return output.toByteArray();
    }
}
