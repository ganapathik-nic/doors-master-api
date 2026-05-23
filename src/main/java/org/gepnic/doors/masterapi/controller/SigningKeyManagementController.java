package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.dto.CertificateGenerationRequest;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/governance/signing-keys")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class SigningKeyManagementController {

    private final DoorsSigningCertificateRepository certRepository;
    private final String keysDirectoryPath;

    public SigningKeyManagementController(
            DoorsSigningCertificateRepository certRepository,
            @Value("${crypto.keys-directory:./security/keys/}") String keysDirectoryPath) { // 🚀 FIXED: Corrected path interpolation mapping token layout
        this.certRepository = certRepository;
        this.keysDirectoryPath = keysDirectoryPath;
    }

    /**
     * 📋 1. FETCH ALL REGISTERED CERTIFICATE PROFILES (Live Pinned, History DESC)
     */
    @GetMapping("/list")
    public ResponseEntity<?> getAllCertificates() {
        try {
            // Invokes custom database query context ordering layer
            List<DoorsSigningCertificate> list = certRepository.findAllWithLivePinnedToTop();
            return ResponseEntity.ok(Map.of("success", true, "data", list));
        } catch (Exception e) {
            log.error("Failed to query certificate profiles collection layout matrix", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Database retrieval error: " + e.getMessage()));
        }
    }

    /**
     * 🔄 2. HOT-SWAP / ACTIVATE A SPECIFIC SIGNING KEY VERSION
     */
    @PostMapping("/{id}/activate")
    @Transactional
    public ResponseEntity<?> activateKeyVersion(@PathVariable Long id) {
        DoorsSigningCertificate targetCert = certRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Target signing key index profile not found."));

        // Ensure physical binary matching key private asset exists on disk before swapping pointers
        File privateKeyFile = new File(keysDirectoryPath, targetCert.getKeyId() + ".der");
        if (!privateKeyFile.exists()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, 
                    "message", "Verification failed. The private key file '" + targetCert.getKeyId() + ".der' is missing from the server drive storage."
            ));
        }

        // Deactivate all current keys running globally inside the matrix
        certRepository.findAll().forEach(c -> c.setActive(false));

        // Flag target profile row entry active
        targetCert.setActive(true);
        targetCert.setActivatedAt(LocalDateTime.now());
        certRepository.save(targetCert);

        log.info("DOORS-GOVERNANCE: PKI active signing context hot-swapped to target KID: [{}]", targetCert.getKeyId());
        return ResponseEntity.ok(Map.of("success", true, "message", "Dynamic PKI context rotation complete. Key '" + targetCert.getKeyId() + "' is now active."));
    }

    /**
     * ⚡ 3. AUTOMATED ON-DEMAND KEY PAIR GENERATOR PIPELINE
     */
    @PostMapping("/generate")
    @Transactional
    public ResponseEntity<?> generateKeyPairOnServer(@RequestBody CertificateGenerationRequest request) {
        if (request.getKeyId() == null || request.getKeyId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Key Identifier string sequence is required."));
        }

        String sanitizedKeyId = request.getKeyId().replaceAll("[^a-zA-Z0-9\\-_]", "").toLowerCase();

        if (certRepository.findByKeyId(sanitizedKeyId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "A key version with ID '" + sanitizedKeyId + "' already exists in the metadata index registry."));
        }

        try {
            File dir = new File(keysDirectoryPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // A. Trigger Native Cryptographic Engine inside the JVM barrier
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            // B. Write Private Key to Disk as standard raw binary byte payload (.der format)
            File privateKeyFile = new File(dir, sanitizedKeyId + ".der");
            try (FileOutputStream fos = new FileOutputStream(privateKeyFile)) {
                fos.write(keyPair.getPrivate().getEncoded());
            }

            // C. Compile and Write Public Key to Disk as clean standard X.509 Base64 text string (.pem format)
            File publicKeyFile = new File(dir, sanitizedKeyId + ".pem");
            String base64PublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String pemContent = "-----BEGIN PUBLIC KEY-----\n" +
                                base64PublicKey.replaceAll("(.{64})", "$1\n") +
                                "\n-----END PUBLIC KEY-----";
            try (FileOutputStream fos = new FileOutputStream(publicKeyFile)) {
                fos.write(pemContent.getBytes(StandardCharsets.UTF_8));
            }

            // D. Persist the harmless track record metadata block directly into PostgreSQL
            DoorsSigningCertificate newCert = new DoorsSigningCertificate();
            newCert.setId(null); // Guarantees Hibernate builds a clean INSERT instead of MERGE
            newCert.setKeyId(sanitizedKeyId);
            newCert.setDisplayName(request.getDisplayName() != null ? request.getDisplayName() : "Auto Generated Key Pair V" + sanitizedKeyId);
            newCert.setActive(false); 
            newCert.setExpiryAt(LocalDateTime.now().plusYears(1));
            
            certRepository.saveAndFlush(newCert);

            log.info("DOORS-GOVERNANCE: Native server keypair generated successfully under KID: [{}]", sanitizedKeyId);
            return ResponseEntity.ok(Map.of(
                    "success", true, 
                    "message", "Cryptographic engine generation successful. Filenames written: " + sanitizedKeyId + ".der / .pem"
            ));

        } catch (Exception ex) {
            log.error("Internal cryptographic runtime routine error", ex);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Internal crypto routine error: " + ex.getMessage()));
        }
    }

    /**
     * 📂 4. CA UPLOAD PIPELINE: IMPORTS OFFICIAL CA-SIGNED X.509 CERTIFICATES
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<?> uploadCaCertificate(
            @RequestParam("keyId") String keyId,
            @RequestParam("displayName") String displayName,
            @RequestParam("privateKeyFile") MultipartFile privateKeyFile,
            @RequestParam("certificateFile") MultipartFile certificateFile) {

        if (keyId == null || keyId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Key Identifier is mandatory."));
        }

        String sanitizedKeyId = keyId.replaceAll("[^a-zA-Z0-9\\-_]", "").toLowerCase();

        if (certRepository.findByKeyId(sanitizedKeyId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Key ID '" + sanitizedKeyId + "' already exists."));
        }

        try {
            // Read Private Key Bytes from Upload Stream
            byte[] privKeyBytes = privateKeyFile.getBytes();
            String privKeyContent = new String(privKeyBytes, StandardCharsets.UTF_8);

            if (!privKeyContent.contains("BEGIN PRIVATE KEY") && !privKeyContent.contains("BEGIN RSA PRIVATE KEY")) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid file format. Private Key must be a valid PEM file."));
            }

            // Read Public Certificate chain text content
            String certContent = new BufferedReader(new InputStreamReader(certificateFile.getInputStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));

            if (!certContent.contains("BEGIN CERTIFICATE")) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid file format. Certificate must be a valid X.509 PEM file."));
            }

            File dir = new File(keysDirectoryPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Convert and write Private Key to Disk (.der binary helper style)
            String cleanPrivKey = privKeyContent
                    .replaceAll("-----\\s*BEGIN[^-]*-----", "")
                    .replaceAll("-----\\s*END[^-]*-----", "")
                    .replaceAll("\\s", "");
            byte[] decodedPrivBytes = Base64.getDecoder().decode(cleanPrivKey);

            File targetPrivateKeyFile = new File(dir, sanitizedKeyId + ".der");
            try (FileOutputStream fos = new FileOutputStream(targetPrivateKeyFile)) {
                fos.write(decodedPrivBytes);
            }

            // Write Public Certificate chain to Disk directly (.pem text file format)
            File targetPublicKeyFile = new File(dir, sanitizedKeyId + ".pem");
            try (FileOutputStream fos = new FileOutputStream(targetPublicKeyFile)) {
                fos.write(certContent.getBytes(StandardCharsets.UTF_8));
            }

            // Commit metadata profile parameters row pointer to PostgreSQL
            DoorsSigningCertificate uploadedCert = new DoorsSigningCertificate();
            uploadedCert.setId(null);
            uploadedCert.setKeyId(sanitizedKeyId);
            uploadedCert.setDisplayName(displayName != null && !displayName.trim().isEmpty() ? displayName : "CA Signed Token: " + sanitizedKeyId);
            uploadedCert.setActive(false); 
            uploadedCert.setExpiryAt(LocalDateTime.now().plusYears(2)); 

            certRepository.saveAndFlush(uploadedCert);

            log.info("DOORS-GOVERNANCE: Official CA certificate bundle imported successfully under KID: [{}]", sanitizedKeyId);
            return ResponseEntity.ok(Map.of(
                    "success", true, 
                    "message", "Official CA certificate bundle imported successfully. Target key mapping: " + sanitizedKeyId
            ));

        } catch (Exception ex) {
            log.error("Failed importing uploaded certificate assets bundle context profiles", ex);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Import pipeline crash: " + ex.getMessage()));
        }
    }
}