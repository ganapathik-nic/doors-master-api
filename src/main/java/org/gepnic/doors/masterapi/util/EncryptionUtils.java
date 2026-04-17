package org.gepnic.doors.masterapi.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EncryptionUtils {
    private static final String ALGORITHM = "AES";
    private static final String DOORS_KEY = "D00RS-NIC-SECURE-2026-KEY!"; 

    public static String decrypt(String encryptedData) throws Exception {
        // Use the first 16 bytes of your key
        byte[] keyBytes = DOORS_KEY.substring(0, 16).getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        
        // 🛡️ Explicitly define the transformation
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}