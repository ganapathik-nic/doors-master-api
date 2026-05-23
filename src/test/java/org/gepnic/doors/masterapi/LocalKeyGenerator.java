package org.gepnic.doors.masterapi;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 🛠️ UTILITY FOR WINDOWS DEV ENVIRONMENT
 * Generates valid .pem files without needing OpenSSL installed.
 */
public class LocalKeyGenerator {
    public static void main(String[] args) {
        // Target directory path matching your Spring application.yml fallback
        String targetDir = "./security/keys/";
        
        try {
            // Create directories if they do not exist
            Files.createDirectories(Paths.get(targetDir));
            
            System.out.println("Initializing 2048-bit RSA Keypair Generator...");
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair pair = keyGen.generateKeyPair();
            
            Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
            
            // 1. Write the Private Key (PKCS#8 Format)
            String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + 
                    encoder.encodeToString(pair.getPrivate().getEncoded()) + 
                    "\n-----END PRIVATE KEY-----\n";
            try (FileWriter fw = new FileWriter(targetDir + "doors_private.pem")) {
                fw.write(privateKeyPem);
            }
            System.out.println("✅ Created: " + targetDir + "doors_private.pem");
            
            // 2. Write the Public Key (X.509 Format)
            String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + 
                    encoder.encodeToString(pair.getPublic().getEncoded()) + 
                    "\n-----END PUBLIC KEY-----\n";
            try (FileWriter fw = new FileWriter(targetDir + "doors_public.pem")) {
                fw.write(publicKeyPem);
            }
            System.out.println("✅ Created: " + targetDir + "doors_public.pem");
            
            System.out.println("\n🎉 Success! Your development keys are ready. You can now start your Master API application.");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to generate key files: " + e.getMessage());
            e.printStackTrace();
        }
    }
}