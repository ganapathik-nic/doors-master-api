package org.gepnic.doors.masterapi.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.ApiClientRepository; 
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.gepnic.doors.masterapi.service.CertificateStorageService;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/gateway")
@RequiredArgsConstructor
public class ExternalGatewayController {

    private final ReportViewerService reportService;
    private final ApiClientRepository apiClientRepository; 
    
    // 🚀 NEW SECURE LAYER DEPENDENCIES: Driven dynamically via governance updates
    private final CertificateStorageService certificateStorageService;
    private final DoorsSigningCertificateRepository certRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    // ⏱️ Session RAM Cache Policies
    private static final Map<String, String> keyRegistryCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> keyExpiryTracker = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    // Base directory path config fallback to safely resolve public components for dynamic JWKS evaluation
    @Value("${crypto.keys-directory:./security/keys/}")
    private String keysDirectoryPath;

    private final String jwksExponentE = "AQAB"; // Standard 65537 serialization constant

    /**
     * 🛡️ Run-time Safety Environment Check
     * Validates that an active signing metadata pointer exists in PostgreSQL without breaking boot initialization.
     */
    @PostConstruct
    public void verifySigningEnvironmentOnStartup() {
        try {
            log.info("🔍 [STARTUP-AUDIT] Validating system active signing context pointer mapping...");
            certRepository.findByIsActiveTrue().ifPresentOrElse(
                cert -> log.info("✅ Active key registry configuration initialized under KID version context: [{}]", cert.getKeyId()),
                () -> log.warn("⚠️ SYSTEM INITIALIZATION WARNING: No active signing key flagged in database table yet!")
            );
        } catch (Exception e) {
            log.error("❌ Critical safety audit initialization alert: {}", e.getMessage());
        }
    }

    /**
     * 🔓 PUBLIC JWKS ALIGNMENT GATEWAY (RFC 7517)
     * Dynamically builds the JWK payload on-demand by fetching the current live database active key identifier.
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<?> getPublicJwks() {
        try {
            // Find whichever record is currently flagged active in our PostgreSQL configuration table
            DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                    .orElseThrow(() -> new IllegalStateException("No active signing certificate flagged in database registry."));

            // Resolve the matching public key asset filename from disk
            File publicKeyFile = new File(keysDirectoryPath, activeCert.getKeyId() + ".pem");
            if (!publicKeyFile.exists()) {
                throw new java.io.FileNotFoundException("Public key file matching active reference metadata was missing from disk structure.");
            }

            // Extract the public key contents
            String pubContent = Files.readString(publicKeyFile.toPath(), StandardCharsets.UTF_8)
                    .replaceAll("-----\\s*BEGIN[^-]*-----", "")
                    .replaceAll("-----\\s*END[^-]*-----", "")
                    .replaceAll("\\s", "");

            byte[] pubBytes = Base64.getDecoder().decode(pubContent);
            java.security.interfaces.RSAPublicKey rsaPub = (java.security.interfaces.RSAPublicKey) 
                    KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubBytes));

            // Compute standard base64url modulus for JWK compatibility mapping
            String calculatedModulusN = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPub.getModulus().toByteArray());

            Map<String, Object> jwkElement = new LinkedHashMap<>();
            jwkElement.put("kty", "RSA");
            jwkElement.put("use", "sig");
            jwkElement.put("alg", "RS256");
            jwkElement.put("kid", activeCert.getKeyId());
            jwkElement.put("n", calculatedModulusN);
            jwkElement.put("e", jwksExponentE);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("keys", List.of(jwkElement)));

        } catch (Exception e) {
            log.error("💥 Failed to resolve and build public JWKS output dynamically", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "JWKS Engine Error: " + e.getMessage()));
        }
    }

    @PostMapping("/handshake")
    public ResponseEntity<?> establishCryptographicHandshake(
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, String> payload) {
        
        if (!apiClientRepository.existsByApiKeyAndActiveTrue(apiKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Unauthorized API token.", 403));
        }

        boolean isEncryptionEnabled = apiClientRepository.checkEncryptionStatusByApiKey(apiKey).orElse(true);
        if (!isEncryptionEnabled) {
            return ResponseEntity.ok(ApiResponse.success(null, "Bypassed. Channel processing raw string inputs."));
        }

        String incomingPublicKey = payload.get("publicKey");
        if (incomingPublicKey == null || incomingPublicKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("Malformed Public Key payload block.", 400));
        }

        keyRegistryCache.put(apiKey, incomingPublicKey.trim());
        keyExpiryTracker.put(apiKey, System.currentTimeMillis());

        return ResponseEntity.ok(ApiResponse.success(null, "Handshake accepted. Window clear for 30 minutes."));
    }

    @PostMapping("/orchestrate/{uniqueName}")
    public ResponseEntity<?> proxyOrchestration(
            @PathVariable String uniqueName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> payload) {

        String authenticatedClient = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) payload.get("params");
            @SuppressWarnings("unchecked")
            List<String> agentIds = (List<String>) payload.get("agentIds");

            ReportExecutionRequest request = ReportExecutionRequest.builder()
                    .queryUniqueName(uniqueName)
                    .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
                    .performedBy(authenticatedClient)
                    .params(params)
                    .build();

            ReportResult result = reportService.executeReport(request);
            List<Map<String, Object>> resultList = result.data();

            String rawJsonOutput = mapper.writeValueAsString(ApiResponse.success(resultList, "Orchestration complete"));

            boolean isEncryptionEnabled = apiClientRepository.checkEncryptionStatusByApiKey(apiKey).orElse(true);
            if (!isEncryptionEnabled) {
                return ResponseEntity.ok().header("X-Content-Secure", "false").contentType(MediaType.APPLICATION_JSON).body(rawJsonOutput);
            }

            // 🛡️ Evaluate Session Deadlines (30-Minute Tracking Check)
            String clientPublicKeyBase64 = keyRegistryCache.get(apiKey);
            Long registrationTimestamp = keyExpiryTracker.get(apiKey);
            long currentTime = System.currentTimeMillis();

            if (clientPublicKeyBase64 == null || registrationTimestamp == null || (currentTime - registrationTimestamp > CACHE_TTL_MS)) {
                keyRegistryCache.remove(apiKey);
                keyExpiryTracker.remove(apiKey);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Session missing or expired. Refresh Handshake.", 403));
            }

            // 🚀 STEP 1: Dynamically resolve the current live active signature metadata record from database
            DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                    .orElseThrow(() -> new IllegalStateException("Active database signature token registration configuration missing."));

            // 🚀 STEP 2: Use your secure CertificateStorageService to stream the private key asset from server drive
            PrivateKey runtimeSigningPrivateKey = certificateStorageService.getActiveSigningPrivateKey();

            // 1. Core PKI Digital Signature Compilation via Server Private Key
            java.security.Signature privateSignature = java.security.Signature.getInstance("SHA256withRSA");
            privateSignature.initSign(runtimeSigningPrivateKey);
            privateSignature.update(rawJsonOutput.getBytes(StandardCharsets.UTF_8));
            String digitalSignature = Base64.getEncoder().encodeToString(privateSignature.sign());

            // 2. Generate Random Symmetric Processing Keys (AES-256)
            KeyGenerator aesKeyGen = KeyGenerator.getInstance("AES");
            aesKeyGen.init(256);
            SecretKey dynamicAesKey = aesKeyGen.generateKey();

            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, dynamicAesKey, new IvParameterSpec(iv));
            byte[] encryptedDataBytes = aesCipher.doFinal(rawJsonOutput.getBytes(StandardCharsets.UTF_8));

            // 3. Encrypt the short-lived AES key using Client's Public Key
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(clientPublicKeyBase64));
            PublicKey rsaPublicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey);
            byte[] encryptedAesKeyBytes = rsaCipher.doFinal(dynamicAesKey.getEncoded());

            return ResponseEntity.ok()
                    .header("X-Content-Secure", "true")
                    .header("X-DOORS-KID", activeCert.getKeyId()) // 🚀 STEP 3: Binds the live un-hardcoded dynamic key hint header response to consumer
                    
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                        "encryptedKey", Base64.getEncoder().encodeToString(encryptedAesKeyBytes),
                        "iv", Base64.getEncoder().encodeToString(iv),
                        "secureData", Base64.getEncoder().encodeToString(encryptedDataBytes),
                        "signature", digitalSignature
                    ));

        } catch (Exception e) {
            log.error("Orchestration processing failure occurred", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("System Error: " + e.getMessage(), 500));
        }
    }
}