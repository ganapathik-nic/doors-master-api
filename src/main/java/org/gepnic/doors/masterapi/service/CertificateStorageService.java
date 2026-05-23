package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Service
public class CertificateStorageService {

    private final DoorsSigningCertificateRepository certRepository;
    private final String keysDirectoryPath;

    public CertificateStorageService(
            DoorsSigningCertificateRepository certRepository,
            @Value("${crypto.keys-directory}") String keysDirectoryPath) {
        this.certRepository = certRepository;
        this.keysDirectoryPath = keysDirectoryPath;
    }

    /**
     * 🚀 Dynamic Resolver: Finds the active DB record, then pulls its raw matching Private Key from disk
     */
    public PrivateKey getActiveSigningPrivateKey() throws Exception {
        DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("💥 CONFIG FAULT: No active signing certificate flagged in database registry!"));

        // Construct path relative to convention base: e.g., ./security/keys/doors-pki-v1.der
        File privateKeyFile = new File(keysDirectoryPath, activeCert.getKeyId() + ".der");
        
        if (!privateKeyFile.exists()) {
            throw new java.io.FileNotFoundException("Missing private key file on disk: " + privateKeyFile.getAbsolutePath());
        }

        byte[] keyBytes = Files.readAllBytes(privateKeyFile.toPath());
        
        // Handle potential PEM string wrapping to raw binary bytes extraction if saved as standard ASCII text
        String keyContent = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (keyContent.contains("-----BEGIN PRIVATE KEY-----")) {
            keyContent = keyContent
                    .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                    .replaceAll("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            keyBytes = Base64.getDecoder().decode(keyContent);
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}