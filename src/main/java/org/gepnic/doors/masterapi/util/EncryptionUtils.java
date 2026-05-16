package org.gepnic.doors.masterapi.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EncryptionUtils {
    private static final String ALGORITHM = "AES";
    private static final String DOORS_KEY = "D00RS-NIC-SECURE-2026-KEY!"; 
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
public static String encrypt(String data) throws Exception {
    // This uses your hardcoded D00RS-NIC-SECURE key
    return encrypt(data, DOORS_KEY); 
}
    // 🚀 ADD THIS: Encryption method for API sharing
     public static String encrypt(String data, String providedKey) throws Exception {
    // 🛡️ Security: Take the first 16 chars of the dynamic API Key
    byte[] keyBytes = providedKey.substring(0, 16).getBytes(StandardCharsets.UTF_8);
    
    SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
    
    byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(encryptedBytes);
}
    public static String decrypt(String encryptedData) throws Exception {
        byte[] keyBytes = DOORS_KEY.substring(0, 16).getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}