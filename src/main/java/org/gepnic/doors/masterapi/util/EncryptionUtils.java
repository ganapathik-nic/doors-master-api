package org.gepnic.doors.masterapi.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class EncryptionUtils {

    private static final String ALGORITHM = "AES";

    private static final String TRANSFORMATION =
            "AES/ECB/PKCS5Padding";

    private static final String DOORS_KEY =
            "D00RS-NIC-SECURE-2026-KEY!";

    private EncryptionUtils() {
        // Utility class
    }

    /**
     * Encrypt using the existing static DOORS key.
     */
    public static String encrypt(
            String data
    ) throws Exception {
        return encrypt(data, DOORS_KEY);
    }

    /**
     * Decrypt using the existing static DOORS key.
     */
    public static String decrypt(
            String encryptedData
    ) throws Exception {
        return decrypt(encryptedData, DOORS_KEY);
    }

    /**
     * Encrypt using a supplied key, including the JWT hybrid key.
     */
    public static String encrypt(
            String data,
            String providedKey
    ) throws Exception {

        if (data == null) {
            throw new IllegalArgumentException(
                    "Data to encrypt cannot be null"
            );
        }

        SecretKeySpec secretKey =
                createSecretKey(providedKey);

        Cipher cipher =
                Cipher.getInstance(TRANSFORMATION);

        cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey
        );

        byte[] encryptedBytes = cipher.doFinal(
                data.getBytes(StandardCharsets.UTF_8)
        );

        return Base64.getEncoder()
                .encodeToString(encryptedBytes);
    }

    /**
     * Decrypt using a supplied key, including the JWT hybrid key.
     */
    public static String decrypt(
            String encryptedData,
            String providedKey
    ) throws Exception {

        if (encryptedData == null ||
                encryptedData.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Encrypted data cannot be empty"
            );
        }

        SecretKeySpec secretKey =
                createSecretKey(providedKey);

        byte[] decodedBytes;

        try {
            decodedBytes = Base64.getDecoder()
                    .decode(encryptedData.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Encrypted data is not valid Base64",
                    exception
            );
        }

        Cipher cipher =
                Cipher.getInstance(TRANSFORMATION);

        cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey
        );

        byte[] decryptedBytes =
                cipher.doFinal(decodedBytes);

        return new String(
                decryptedBytes,
                StandardCharsets.UTF_8
        );
    }

    private static SecretKeySpec createSecretKey(
            String providedKey
    ) {
        if (providedKey == null ||
                providedKey.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Encryption key cannot be empty"
            );
        }

        byte[] suppliedBytes = providedKey.getBytes(
                StandardCharsets.UTF_8
        );

        if (suppliedBytes.length < 16) {
            throw new IllegalArgumentException(
                    "Encryption key must contain " +
                            "at least 16 UTF-8 bytes"
            );
        }

        byte[] aesKeyBytes = new byte[16];

        System.arraycopy(
                suppliedBytes,
                0,
                aesKeyBytes,
                0,
                16
        );

        return new SecretKeySpec(
                aesKeyBytes,
                ALGORITHM
        );
    }
}