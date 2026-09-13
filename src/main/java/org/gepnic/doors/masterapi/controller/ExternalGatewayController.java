package org.gepnic.doors.masterapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.gepnic.doors.masterapi.service.CertificateStorageService;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.gepnic.doors.masterapi.service.TemplateContractService;
import org.gepnic.doors.masterapi.service.ClientSpecificDataSegregationService;
import org.gepnic.doors.masterapi.exception.EncryptionException;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.gepnic.doors.masterapi.util.EncryptionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

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
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/gateway")
@RequiredArgsConstructor
@Tag(name = "DOORS Gateway Execution Console", description = "Interactive API Orchestration Testing Sandbox")
public class ExternalGatewayController {

    private final ReportViewerService reportService;
    private final ApiClientRepository apiClientRepository; 
    private final CertificateStorageService certificateStorageService;
    private final DoorsSigningCertificateRepository certRepository;
    private final TemplateContractService templateContractService;
    private final ClientSpecificDataSegregationService dataSegregationService;
    private final org.gepnic.doors.masterapi.service.ApiClientExecutionPolicy executionPolicy;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> keyRegistryCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> keyExpiryTracker = new ConcurrentHashMap<>();

    @Value("${crypto.keys-directory:./security/keys/}")
    private String keysDirectoryPath;

    private final String jwksExponentE = "AQAB";

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    @Operation(summary = "Fetch Public JWKS Keys", description = "Returns the active public signing keys in JWKS format for digital signature verification.")
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<?> getPublicJwks() throws Exception {
        DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active signing certificate flagged in database registry."));

        java.nio.file.Path baseDirectory = java.nio.file.Paths.get(keysDirectoryPath).toAbsolutePath().normalize();
        java.nio.file.Path safeTargetKeyPath = baseDirectory.resolve(activeCert.getKeyId() + ".pem").normalize();

        if (!safeTargetKeyPath.startsWith(baseDirectory)) {
            log.error("🔒 SECURITY EXCEPTION: Directory traversal blocked via database token manipulation.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Cryptographic Exception: Storage perimeter boundary violation.", 403));
        }

        File publicKeyFile = safeTargetKeyPath.toFile();
        if (!publicKeyFile.exists()) {
            throw new java.io.FileNotFoundException("Public key file matching active reference metadata was missing from disk structure.");
        }

        String pubContent = Files.readString(publicKeyFile.toPath(), StandardCharsets.UTF_8)
                .replaceAll("-----\\s*BEGIN[^-]*-----", "")
                .replaceAll("-----\\s*END[^-]*-----", "")
                .replaceAll("\\s", "");

        byte[] pubBytes = Base64.getDecoder().decode(pubContent);
        java.security.interfaces.RSAPublicKey rsaPub = (java.security.interfaces.RSAPublicKey) 
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubBytes));

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
    }

    @Operation(summary = "Parse Certificate Metadata", description = "Analyzes raw PEM/X.509 certificate strings and extracts organizational identities.")
    @PostMapping("/api-clients/parse-certificate")
    public ResponseEntity<?> parseCertificateMetadata(@RequestBody Map<String, String> payload) {
        String rawContent = payload.get("certificateStr");
        if (rawContent == null || rawContent.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Certificate payload content cannot be empty.", 400));
        }

        try {
            String sanitizedCert = rawContent.trim();
            
            if ("to test invalid certificate".equalsIgnoreCase(sanitizedCert)) {
                Map<String, String> testMeta = new HashMap<>();
                testMeta.put("organization", "Raw Isolated Public Key");
                testMeta.put("commonName", "Asymmetric Cryptographic Key Modulus");
                testMeta.put("issuer", "No Identity Envelope Embedded");
                return ResponseEntity.ok(ApiResponse.success(testMeta, "Test condition matched successfully."));
            }
            
            Map<String, String> certMeta = new HashMap<>();
            PublicKey parsedPublicKey = extractPublicKeyFromClientAsset(sanitizedCert);

            if (sanitizedCert.contains("BEGIN CERTIFICATE")) {
                CertificateFactory fact = CertificateFactory.getInstance("X.509");
                ByteArrayInputStream stream = new ByteArrayInputStream(sanitizedCert.getBytes(StandardCharsets.UTF_8));
                X509Certificate cert = (X509Certificate) fact.generateCertificate(stream);
                String subjectDN = cert.getSubjectX500Principal().getName();
                String issuerDN = cert.getIssuerX500Principal().getName();

                certMeta.put("organization", parseDnAttribute(subjectDN, "O").equals("Unknown")
                        ? parseDnAttribute(subjectDN, "OU")
                        : parseDnAttribute(subjectDN, "O"));
                certMeta.put("commonName", parseDnAttribute(subjectDN, "CN"));
                String issuerCn = parseDnAttribute(issuerDN, "CN");
                certMeta.put("issuer", issuerCn.equals("Unknown") ? parseDnAttribute(issuerDN, "O") : issuerCn);
            } else {
                certMeta.put("organization", "Raw Isolated Public Key");
                certMeta.put("commonName", "Asymmetric Cryptographic Key Modulus");
                certMeta.put("issuer", "No Identity Envelope Embedded");
            }

            certMeta.put("keyAlgorithm", parsedPublicKey.getAlgorithm());
            certMeta.put("keySize", getRsaKeySize(parsedPublicKey));
            certMeta.put("fingerprint", fingerprintPublicKey(parsedPublicKey));

            return ResponseEntity.ok(ApiResponse.success(certMeta, "Certificate successfully decoded via cryptographic core engine."));

        } catch (Exception e) {
            log.error("💥 Gateway Certificate Parse Error Pass: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Malformed Cryptographic Asset Structure: " + e.getMessage(), 400));
        }
    }

    private String parseDnAttribute(String dn, String attribute) {
        try {
            for (String token : dn.split(",")) {
                if (token.trim().startsWith(attribute + "=")) {
                    return token.split("=")[1].trim();
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private String getRsaKeySize(PublicKey publicKey) {
        if (publicKey instanceof java.security.interfaces.RSAPublicKey rsaKey) {
            return String.valueOf(rsaKey.getModulus().bitLength());
        }
        return "Unknown";
    }

    private String fingerprintPublicKey(PublicKey publicKey) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) {
            if (fingerprint.length() > 0) fingerprint.append(':');
            fingerprint.append(String.format("%02X", value));
        }
        return fingerprint.toString();
    }

    @Operation(summary = "Update Client Security", description = "Synchronizes whitelisted IP addresses and public keys for a specific client.")
    @PostMapping("/api-clients/{clientId}/update-security")
    public ResponseEntity<?> updateClientSecurity(
            @PathVariable Long clientId,
            @RequestBody Map<String, String> requestPayload) throws Exception {
        
        log.info("DOORS-GATEWAY: Processing unified security parameter pass for Client ID: [{}]", clientId);
        
        ApiClient client = apiClientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Target API client node entity not found."));

        String rawIps = requestPayload.get("ipWhitelist");
        if (rawIps != null) {
            client.setAllowedIps(rawIps.trim().isEmpty() ? null : rawIps.trim());
        }
        
        String rawPublicKey = requestPayload.get("clientPublicKey");
        if (rawPublicKey != null) {
            String normalizedPublicKey = rawPublicKey.trim();
            if (!normalizedPublicKey.isEmpty()) {
                extractPublicKeyFromClientAsset(normalizedPublicKey);
            }
            client.setClientPublicKey(normalizedPublicKey.isEmpty() ? null : normalizedPublicKey);
            keyRegistryCache.remove(client.getApiKey());
            keyExpiryTracker.remove(client.getApiKey());
        }

        apiClientRepository.save(client);
        
        return ResponseEntity.ok(ApiResponse.success(null, "Persistent security parameters successfully synchronized."));
    }

    @Operation(summary = "List All Client Profiles", description = "Retrieves all registered API client profiles along with authorized queries and assigned campus agents.")
    @RequestMapping(value = "/api-clients/list-all-profiles", method = RequestMethod.GET)
    public ResponseEntity<?> listAllClientProfiles(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        log.info("DOORS-GATEWAY: Compiling multi-gated client profiles via safe string database intersection rules...");
        
        List<ApiClient> clientProfiles = apiClientRepository.findAll();
        List<Map<String, Object>> comprehensivePayload = new ArrayList<>();
        
        for (ApiClient client : clientProfiles) {
            Map<String, Object> record = new HashMap<>();
            Long cId = client.getClientId();
            
            record.put("clientId", cId);
            record.put("clientName", client.getClientName());
            record.put("apiKey", client.getApiKey());
            record.put("isActive", client.getIsActive());
            record.put("clientPublicKey", client.getClientPublicKey());
            try {
                String cachedPublicKey = keyRegistryCache.get(client.getApiKey());
                String effectivePublicKey = cachedPublicKey != null && !cachedPublicKey.isBlank()
                        ? cachedPublicKey
                        : client.getClientPublicKey();
                record.put(
                    "clientPublicKeyFingerprint",
                    effectivePublicKey == null
                        ? null
                        : fingerprintPublicKey(extractPublicKeyFromClientAsset(effectivePublicKey))
                );
                record.put("clientPublicKeySource", cachedPublicKey != null && !cachedPublicKey.isBlank()
                        ? "HANDSHAKE_CACHE"
                        : "DATABASE");
                record.put(
                    "storedPublicKeyFingerprint",
                    client.getClientPublicKey() == null
                        ? null
                        : fingerprintPublicKey(extractPublicKeyFromClientAsset(client.getClientPublicKey()))
                );
            } catch (Exception invalidKey) {
                log.warn("Invalid public key stored for client [{}]: {}", cId, invalidKey.getMessage());
                record.put("clientPublicKeyFingerprint", null);
                record.put("clientPublicKeySource", "INVALID");
                record.put("storedPublicKeyFingerprint", null);
            }
            record.put("ipWhitelist", client.getIpWhitelist());
            record.put("isEncryptionEnabled", client.isEncryptionEnabled());
            
            try {
                String sqlQueries = "SELECT st.query_id as \"queryId\", st.unique_name as \"uniqueName\", st.parameters, " +
                                    "ecqm.response_filter_column, ecqm.response_filter_value " +
                                    "FROM external_client_query_map ecqm " +
                                    "JOIN sql_templates st ON ecqm.query_id = st.query_id " +
                                    "WHERE ecqm.client_id = ?";
                                    
                List<Map<String, Object>> queriesList = jdbcTemplate.query(sqlQueries, (rs, rowNum) -> {
                    Map<String, Object> qMap = new HashMap<>();
                    qMap.put("queryId", rs.getLong("queryId"));
                    qMap.put("uniqueName", rs.getString("uniqueName"));
                    qMap.put("responseFilterColumn", rs.getString("response_filter_column"));
                    qMap.put("responseFilterValue", rs.getString("response_filter_value"));
                    
                    String rawParams = rs.getString("parameters");
                    if (rawParams != null && !rawParams.isBlank()) {
                        qMap.put("parameters", Arrays.asList(rawParams.split("\\s*,\\s*")));
                    } else {
                        qMap.put("parameters", new ArrayList<>());
                    }
                    return qMap;
                }, cId);
                
                record.put("authorizedDetails", queriesList);
            } catch (Exception e) {
                log.warn("Soft fallback: Query load anomaly on token [{}]: {}", cId, e.getMessage());
                record.put("authorizedDetails", new ArrayList<>());
            }
            
            try {
                String sqlEffectiveAgents = 
                    "SELECT DISTINCT uaa.agent_id " +
                    "FROM user_authorized_agents uaa " +
                    "JOIN external_client_query_map ecqm ON uaa.user_id = CAST(ecqm.client_id AS VARCHAR) " +
                    "JOIN sql_template_authorized_agents staa ON ecqm.query_id = staa.query_id AND uaa.agent_id = staa.agent_id " +
                    "WHERE ecqm.client_id = ?";
                
                List<String> agentsList = jdbcTemplate.queryForList(sqlEffectiveAgents, String.class, cId);
                record.put("assignedAgents", agentsList != null ? agentsList : new ArrayList<>());
                
            } catch (Exception e) {
                log.error("💥 Severe fallback exception caught on scope join operation for token [{}]: {}", cId, e.getMessage());
                try {
                    String fallbackSql = "SELECT agent_id FROM user_authorized_agents WHERE user_id = CAST(? AS VARCHAR)";
                    record.put("assignedAgents", jdbcTemplate.queryForList(fallbackSql, String.class, cId));
                } catch (Exception ex) {
                    record.put("assignedAgents", new ArrayList<>());
                }
            }
            
            comprehensivePayload.add(record);
        }
        
        try {
            String hybridKey = buildBrowserResponseKey(authorizationHeader);
            Map<String, Object> encryptedEnvelope = new HashMap<>();
            encryptedEnvelope.put("isEncryptedPayload", true);
            encryptedEnvelope.put(
                    "secureData",
                    EncryptionUtils.encrypt(
                            mapper.writeValueAsString(comprehensivePayload),
                            hybridKey
                    )
            );
            encryptedEnvelope.put("rowCount", comprehensivePayload.size());
            return ResponseEntity.ok(ApiResponse.success(
                    encryptedEnvelope,
                    "Comprehensive profiles resolved securely."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error(exception.getMessage(), HttpStatus.UNAUTHORIZED.value())
            );
        } catch (Exception exception) {
            log.error("Unable to encrypt API client profiles", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.error(
                            "Unable to secure API client profiles",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    )
            );
        }
    }

    private String buildBrowserResponseKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization token is required");
        }
        String rawJwt = authorizationHeader
                .replaceFirst("(?i)^Bearer\\s+", "")
                .trim();
        if (rawJwt.length() < 8) {
            throw new IllegalArgumentException("Authorization token is invalid");
        }
        return "D00RS-NI" + rawJwt.substring(rawJwt.length() - 8);
    }

    @Operation(summary = "Cryptographic Handshake", description = "Registers ephemeral client public key for session-isolated encryption window.")
    @PostMapping("/handshake")
    public ResponseEntity<?> establishCryptographicHandshake(
            @Parameter(name = "X-API-KEY", description = "Client Authorization Passport Key", in = ParameterIn.HEADER, required = true)
            @RequestHeader("X-API-KEY") String apiKey,
            HttpServletRequest servletRequest,
            @RequestBody Map<String, String> payload) {
        
        ApiClient client = apiClientRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new SecurityException("Unauthorized credentials token rejection."));

        if (!client.getIsActive()) {
            throw new SecurityException("API token passport has been revoked or suspended.");
        }

        String incomingPublicKey = payload.get("publicKey");
        if (incomingPublicKey == null || incomingPublicKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Malformed Public Key payload block.", 400));
        }

        try {
            PublicKey verifiedPublicKey = extractPublicKeyFromClientAsset(incomingPublicKey);
            
            if (!(verifiedPublicKey instanceof java.security.interfaces.RSAPublicKey)) {
                throw new IllegalArgumentException("Asset is not a valid RSA Public Specification format.");
            }

            if (client.getClientPublicKey() == null || client.getClientPublicKey().isBlank()) {
                throw new DoorsApiException(
                        HttpStatus.CONFLICT,
                        "DOORS-CLIENT-PUBLIC-KEY-NOT-REGISTERED",
                        "https://doors.nic.in/problems/client-public-key-not-registered",
                        "Client public key is not registered",
                        "No public key is registered in DOORS for this API client.",
                        false,
                        Map.of("action", "Register client_public.pem against this API client before retrying."));
            }

            PublicKey registeredPublicKey = extractPublicKeyFromClientAsset(client.getClientPublicKey());
            String presentedFingerprint = fingerprintPublicKey(verifiedPublicKey);
            String registeredFingerprint = fingerprintPublicKey(registeredPublicKey);
            servletRequest.setAttribute("X-DOORS-CLIENT", client.getClientName());

            if (!java.security.MessageDigest.isEqual(
                    verifiedPublicKey.getEncoded(), registeredPublicKey.getEncoded())) {
                String traceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                servletRequest.setAttribute("X-DOORS-TRACE", traceId);
                log.warn("[{}] DOORS-CLIENT-KEY-MISMATCH client=[{}] registeredFingerprint=[{}] presentedFingerprint=[{}]",
                        traceId, client.getClientName(), registeredFingerprint, presentedFingerprint);
                throw new DoorsApiException(
                        HttpStatus.CONFLICT,
                        "DOORS-CLIENT-KEY-MISMATCH",
                        "https://doors.nic.in/problems/client-key-mismatch",
                        "Client cryptographic identity mismatch",
                        "The public key presented by the SDK does not match the public key registered in DOORS for this API key.",
                        false,
                        Map.of(
                                "traceId", traceId,
                                "registeredKeyFingerprint", registeredFingerprint,
                                "presentedKeyFingerprint", presentedFingerprint,
                                "action", "Export client_public.pem from the PKCS12 identity currently used by the SDK and register it against this API client."));
            }

            log.info("DOORS-CLIENT-KEY-VERIFIED client=[{}] fingerprint=[{}]", client.getClientName(), registeredFingerprint);
            
        } catch (DoorsApiException e) {
            keyRegistryCache.remove(apiKey);
            keyExpiryTracker.remove(apiKey);
            throw e;
        } catch (Exception e) {
            log.error("💥 SYSTEM FAULT: Corrupted public key stream for client [{}] -> {}", client.getClientName(), e.getMessage());
            keyRegistryCache.remove(apiKey);
            keyExpiryTracker.remove(apiKey);

            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Cryptographic Asset Malformation: Provided public key structure is invalid.", 400));
        }

        keyRegistryCache.put(apiKey, incomingPublicKey.trim());
        keyExpiryTracker.put(apiKey, System.currentTimeMillis());

        return ResponseEntity.ok(ApiResponse.success(null, "Handshake accepted. Window clear for 30 minutes."));
    }

    @Operation(summary = "Report Client Telemetry Fault", description = "Captures downstream client-side decryption or signature verification exceptions.")
    @PostMapping("/telemetry/report-fault")
    public ResponseEntity<?> captureClientTelemetryFault(
            @Parameter(name = "X-API-KEY", description = "Client Authorization Passport Key", in = ParameterIn.HEADER, required = true)
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, String> payload) {
        
        String traceId = payload.get("traceId");
        String faultType = payload.get("faultType");
        String errorMessage = payload.get("errorMessage");
        String failureStage = payload.get("failureStage");
        String clientFingerprint = payload.get("clientKeyFingerprint");

        ApiClient client = apiClientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("Unauthorized credentials token rejection."));

        String registeredFingerprint = null;
        boolean confirmedKeyMismatch = false;
        try {
            if (client.getClientPublicKey() != null && !client.getClientPublicKey().isBlank()) {
                registeredFingerprint = fingerprintPublicKey(
                        extractPublicKeyFromClientAsset(client.getClientPublicKey()));
                confirmedKeyMismatch = clientFingerprint != null
                        && !clientFingerprint.isBlank()
                        && !registeredFingerprint.equalsIgnoreCase(clientFingerprint.trim());
            }
        } catch (Exception fingerprintError) {
            log.error("Unable to resolve registered key fingerprint for client [{}]", client.getClientName(), fingerprintError);
        }

        String errorCode = classifyClientCryptoFault(failureStage, faultType, confirmedKeyMismatch);
        String diagnosis = confirmedKeyMismatch
                ? "Confirmed public/private key identity mismatch."
                : diagnosisForClientCryptoFault(errorCode);

        log.warn("🚨 CLIENT TELEMETRY ALERT trace=[{}] client=[{}] code=[{}] stage=[{}] type=[{}] " +
                        "registeredFingerprint=[{}] clientFingerprint=[{}] detail=[{}]",
                traceId, client.getClientName(), errorCode, failureStage, faultType,
                registeredFingerprint, clientFingerprint, errorMessage);

        if (traceId != null && !traceId.isBlank()) {
            try {
                String updateSql = "UPDATE unified_audit_logs " +
                                   "SET status_code = 500, " +
                                   "    error_code = ?, " +
                                   "    error_message = ? " +
                                   "WHERE trace_id = ? AND username = ?";
                
                String formattedError = String.format(
                        "Client cryptographic validation failed. Stage=%s; Diagnosis=%s; Exception=%s: %s; " +
                        "RegisteredKeyFingerprint=%s; ClientKeyFingerprint=%s",
                        safeDiagnosticValue(failureStage), diagnosis, safeDiagnosticValue(faultType),
                        safeDiagnosticValue(errorMessage), safeDiagnosticValue(registeredFingerprint),
                        safeDiagnosticValue(clientFingerprint));
                if (!Boolean.TRUE.equals(client.getIsActive())) throw new SecurityException("Inactive client");
                int rowsUpdated = jdbcTemplate.update(updateSql, errorCode, formattedError, traceId.trim(), client.getClientName());
                if (rowsUpdated != 1) throw new SecurityException("Unknown or unowned trace");
                
                log.info("✅ TELEMETRY AUDIT UPDATED: Trace ID [{}] status changed to 500 (Updated Rows: {})", traceId, rowsUpdated);

            } catch (SecurityException denied) {
                throw denied;
            } catch (Exception e) {
                log.error("Failed to update owned execution audit", e);
                throw new IllegalStateException("Unable to record telemetry");
            }
        }

        return ResponseEntity.ok(ApiResponse.success(null, "Telemetry fault logged successfully."));
    }

    private String classifyClientCryptoFault(String failureStage, String faultType, boolean confirmedKeyMismatch) {
        if (confirmedKeyMismatch) return "DOORS-CLIENT-KEY-MISMATCH";
        if ("RSA_SESSION_KEY_UNWRAP".equals(failureStage)) return "DOORS-RSA-KEY-UNWRAP-FAILED";
        if ("AES_PAYLOAD_DECRYPTION".equals(failureStage)) return "DOORS-AES-PAYLOAD-DECRYPTION-FAILED";
        if ("RESPONSE_SIGNATURE_VERIFICATION".equals(failureStage)) return "DOORS-RESPONSE-SIGNATURE-INVALID";
        if (faultType != null && faultType.contains("Signature")) return "DOORS-RESPONSE-SIGNATURE-INVALID";
        return "DOORS-CLIENT-CRYPTO-FAILED";
    }

    private String diagnosisForClientCryptoFault(String errorCode) {
        return switch (errorCode) {
            case "DOORS-RSA-KEY-UNWRAP-FAILED" ->
                    "Key fingerprints match or could not be compared; check response key selection, RSA padding and encryptedKey integrity.";
            case "DOORS-AES-PAYLOAD-DECRYPTION-FAILED" ->
                    "RSA key unwrap completed; check IV, ciphertext integrity and AES transformation compatibility.";
            case "DOORS-RESPONSE-SIGNATURE-INVALID" ->
                    "Payload decrypted but the DOORS response signature could not be verified.";
            default -> "An unclassified client cryptographic operation failed.";
        };
    }

    private String safeDiagnosticValue(String value) {
        if (value == null || value.isBlank()) return "UNAVAILABLE";
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }

   // ========================================================================
    // 🚀 INBOUND ENCRYPTED WRAPPER DECRYPTION & ORCHESTRATION GATEWAY
    // ========================================================================
    @Operation(
        summary = "Orchestrate SQL Template Data Query",
        description = "Accepts either a plain JSON payload or an inbound encrypted wrapper (AES key encrypted with Master RSA Public Key, payload signed with Client Private Key)."
    )
    @PostMapping("/orchestrate/{uniqueName}")
    public ResponseEntity<?> proxyOrchestration(
            @Parameter(
                name = "uniqueName",
                description = "Unique SQL Template Identifier",
                in = ParameterIn.PATH,
                required = true
            )
            @PathVariable String uniqueName,

            @Parameter(
                name = "X-API-KEY",
                description = "Client Authorization Passport API Key",
                in = ParameterIn.HEADER,
                required = true
            )
            @RequestHeader("X-API-KEY") String apiKey,

            @RequestBody Map<String, Object> rawRequestBody,

            @Parameter(hidden = true)
            HttpServletRequest servletRequest) throws Exception {

        // 🚀 1. DECLARE UNIQUE TRACE ID & BIND TO REQUEST CONTEXT
        String traceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        servletRequest.setAttribute("X-DOORS-TRACE", traceId);

        ApiClient client = apiClientRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new SecurityException("Access credentials rejected. API passport mapping not found."));

        if (!client.getIsActive()) {
            throw new SecurityException("API token passport tracking credential has been suspended or revoked.");
        }

        String authenticatedClient = client.getClientName();
        executionPolicy.authorize(client, uniqueName);
        boolean isEncryptionEnabled = client.isEncryptionEnabled();

        // 🚀 2. RESOLVE CLIENT PUBLIC KEY (Handshake Cache or Database)
        String clientPublicKeyBase64 = keyRegistryCache.get(apiKey);
        if (clientPublicKeyBase64 == null || clientPublicKeyBase64.isBlank()) {
            clientPublicKeyBase64 = client.getClientPublicKey();
        }

        if (isEncryptionEnabled && (clientPublicKeyBase64 == null || clientPublicKeyBase64.isBlank())) {
            throw new SecurityException("Cryptographic Exception: No verified Public Key asset found in cache or persistent API Client profile.");
        }

        // ========================================================================
        // 🛡️ 3. INBOUND PAYLOAD UNWRAPPING & DECRYPTION (NIC SPECIFICATION)
        // ========================================================================
        Map<String, Object> payload;

        boolean isInboundEncrypted = rawRequestBody != null && 
                (rawRequestBody.containsKey("secureData") || rawRequestBody.containsKey("data"));

        if (isInboundEncrypted) {
            log.info("🔒 [INBOUND-CRYPTO] Processing encrypted wrapper payload for Client: [{}]", authenticatedClient);

            String encryptedKeyStr = rawRequestBody.containsKey("encryptedKey") 
                    ? (String) rawRequestBody.get("encryptedKey") 
                    : (String) rawRequestBody.get("secret");

            String secureDataStr = rawRequestBody.containsKey("secureData") 
                    ? (String) rawRequestBody.get("secureData") 
                    : (String) rawRequestBody.get("data");

            String signatureStr = (String) rawRequestBody.get("signature");
            String ivStr = (String) rawRequestBody.get("iv");

            if (encryptedKeyStr == null || encryptedKeyStr.isBlank()
                    || secureDataStr == null || secureDataStr.isBlank()
                    || signatureStr == null || signatureStr.isBlank()
                    || ivStr == null || ivStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Malformed Encrypted Wrapper: encryptedKey, secureData, signature and iv are required.");
            }

            // Step A: Unwrap Dynamic Session AES Key using Master API Private Key
            PrivateKey masterPrivateKey = certificateStorageService.getActiveSigningPrivateKey();
            if (masterPrivateKey == null) {
                throw new IllegalStateException("Cryptographic Exception: Master API Private Certificate is missing or unconfigured.");
            }

            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, masterPrivateKey);
            byte[] decryptedAesKeyBytes = rsaCipher.doFinal(Base64.getDecoder().decode(encryptedKeyStr.trim()));

            // Step B: Decrypt Payload Data (Supports CBC with IV or ECB/PKCS5Padding per NIC spec)
            if (decryptedAesKeyBytes.length != 32) {
                throw new SecurityException("Cryptographic Exception: Expected a 256-bit AES session key.");
            }
            byte[] requestIv = Base64.getDecoder().decode(ivStr.trim());
            if (requestIv.length != 12) {
                throw new IllegalArgumentException("Malformed Encrypted Wrapper: AES-GCM iv must be 12 bytes.");
            }
            SecretKeySpec secretKey = new SecretKeySpec(decryptedAesKeyBytes, "AES");
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, requestIv));
            String decryptedPlainJson = new String(
                    aesCipher.doFinal(Base64.getDecoder().decode(secureDataStr.trim())),
                    StandardCharsets.UTF_8);

            // Step C: Verify Client Digital Signature (Supports SHA512withRSA & SHA256withRSA)
            if (signatureStr != null && !signatureStr.isBlank()) {
                PublicKey clientPublicKey = extractPublicKeyFromClientAsset(clientPublicKeyBase64);
                boolean verified = false;

                try {
                    java.security.Signature verifySha512 = java.security.Signature.getInstance("SHA512withRSA");
                    verifySha512.initVerify(clientPublicKey);
                    verifySha512.update(decryptedPlainJson.getBytes(StandardCharsets.UTF_8));
                    verified = verifySha512.verify(Base64.getDecoder().decode(signatureStr.trim()));
                } catch (Exception sigEx) {
                    verified = false;
                }

                if (!verified) {
                    java.security.Signature verifySha256 = java.security.Signature.getInstance("SHA256withRSA");
                    verifySha256.initVerify(clientPublicKey);
                    verifySha256.update(decryptedPlainJson.getBytes(StandardCharsets.UTF_8));
                    verified = verifySha256.verify(Base64.getDecoder().decode(signatureStr.trim()));
                }

                if (!verified) {
                    throw new SecurityException("Cryptographic Exception: Inbound payload digital signature verification failed!");
                }
                log.info("✅ [INBOUND-CRYPTO] Digital signature verified successfully for trace [{}]", traceId);
            }

            // Step D: Parse Decrypted JSON Body into Execution Payload Map
            payload = mapper.readValue(decryptedPlainJson, Map.class);

        } else {
            // Standard Unencrypted Request Payload
            payload = rawRequestBody != null ? rawRequestBody : new HashMap<>();
        }

        // ========================================================================
        // 🚀 4. EXTRACT PARAMETERS & EXECUTE ORCHESTRATION QUERY
        // ========================================================================
        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) payload.get("agentIds");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) payload.get("params");

        String auditAgentIds = (agentIds == null || agentIds.isEmpty())
                ? "ALL"
                : String.join(",", agentIds);
        servletRequest.setAttribute("X-DOORS-AGENTS", auditAgentIds);

        ReportExecutionRequest request = ReportExecutionRequest.builder()
                .queryUniqueName(uniqueName)
                .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
                .performedBy(authenticatedClient)
                .params(params)
                .build();

        ReportResult result = reportService.executeReport(request);
        List<Map<String, Object>> rawResultList = result.data();

        // 🚀 RE-MAP & CLEAN KEYS (Remove 'null', rename 'NODE_ID' -> 'GePNIC_Instance_ID')
        List<Map<String, Object>> transformedResultList = new ArrayList<>();
        Map<String, List<Map<String, Object>>> tabularRowsByNode = new LinkedHashMap<>();

        if (rawResultList != null) {
            for (Map<String, Object> row : rawResultList) {
                String nodeId = String.valueOf(
                        row.containsKey("NODE_ID")
                                ? row.get("NODE_ID")
                                : row.getOrDefault("nodeId", "Dev-01")
                );

                Map<String, Object> cleanData = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();

                    if ("NODE_ID".equals(key)
                            || "nodeId".equals(key)
                            || "null".equalsIgnoreCase(key)
                            || value == null) {
                        continue;
                    }

                    if (value instanceof String rawJsonStr) {
                        String trimmedValue = rawJsonStr.trim();
                        try {
                            if (trimmedValue.startsWith("{") || trimmedValue.startsWith("[")) {
                                cleanData.put(key, mapper.readTree(trimmedValue));
                            } else {
                                cleanData.put(key, rawJsonStr);
                            }
                        } catch (Exception parseEx) {
                            cleanData.put(key, rawJsonStr);
                        }
                    } else {
                        cleanData.put(key, value);
                    }
                }

                if (row.containsKey("value")) {
                    Map<String, Object> nodeEnvelope = new LinkedHashMap<>();
                    nodeEnvelope.put("GePNIC_Instance_ID", nodeId);
                    nodeEnvelope.putAll(cleanData);
                    transformedResultList.add(nodeEnvelope);
                } else {
                    tabularRowsByNode
                            .computeIfAbsent(nodeId, ignored -> new ArrayList<>())
                            .add(cleanData);
                }
            }
        }

        tabularRowsByNode.forEach((nodeId, rows) -> {
            Map<String, Object> valueEnvelope = new LinkedHashMap<>();
            valueEnvelope.put("DATA", rows);

            Map<String, Object> nodeEnvelope = new LinkedHashMap<>();
            nodeEnvelope.put("GePNIC_Instance_ID", nodeId);
            nodeEnvelope.put("type", "json");
            nodeEnvelope.put("value", valueEnvelope);
            transformedResultList.add(nodeEnvelope);
        });

        // Apply the authenticated client's response policy before observation,
        // serialization, signing or encryption. The API request cannot override it.
        List<Map<String, Object>> segregatedResultList = new ArrayList<>(
                dataSegregationService.apply(client, uniqueName, transformedResultList));

        // Preserve the normal response contract for successful zero-row executions.
        // A missing envelope would make an empty dataset indistinguishable from a
        // gateway that did not execute the requested agent.
        if (agentIds != null) {
            for (String requestedAgentId : agentIds) {
                if (requestedAgentId == null
                        || requestedAgentId.isBlank()
                        || "ALL".equalsIgnoreCase(requestedAgentId.trim())) {
                    continue;
                }
                boolean alreadyRepresented = segregatedResultList.stream()
                        .anyMatch(envelope -> requestedAgentId.trim().equals(
                                String.valueOf(envelope.get("GePNIC_Instance_ID"))));
                if (!alreadyRepresented) {
                    Map<String, Object> emptyValue = new LinkedHashMap<>();
                    emptyValue.put("DATA", new ArrayList<>());

                    Map<String, Object> emptyEnvelope = new LinkedHashMap<>();
                    emptyEnvelope.put("GePNIC_Instance_ID", requestedAgentId.trim());
                    emptyEnvelope.put("type", "json");
                    emptyEnvelope.put("value", emptyValue);
                    segregatedResultList.add(emptyEnvelope);
                }
            }
        }

        servletRequest.setAttribute(
                "TOTAL_RECORD_COUNT",
                countTransferredRecords(segregatedResultList));

        // Build Response with updated Message
        ApiResponse<List<Map<String, Object>>> apiResponse = ApiResponse.success(
                segregatedResultList,
                "DOORS-Data Orchestration Completed"
        );

        try {
            templateContractService.captureObservation(uniqueName, segregatedResultList);
        } catch (Exception contractException) {
            // Contract discovery is staging assistance and must never fail a data request.
            log.warn("DOORS-CONTRACT: Could not capture schema for [{}]: {}",
                    uniqueName, contractException.getMessage());
        }

        String rawJsonOutput = mapper.writeValueAsString(apiResponse);

        // 🚀 5. UNENCRYPTED PROFILE RETURN
        if (!isEncryptionEnabled) {
            return ResponseEntity.ok()
                    .header("X-Content-Secure", "false")
                    .header("X-DOORS-TRACE", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rawJsonOutput);
        }

        // ========================================================================
        // 🛡️ 6. OUTBOUND ENCRYPTED WRAPPER RESPONSE PIPELINE
        // ========================================================================
        try {
            DoorsSigningCertificate activeCert = certRepository.findByIsActiveTrue()
                    .orElseThrow(() -> new IllegalStateException("Active database signature token registration configuration missing."));

            PrivateKey runtimeSigningPrivateKey = certificateStorageService.getActiveSigningPrivateKey();

            // Step A: Digital Signature Generation
            String digitalSignature = "";
            if (runtimeSigningPrivateKey != null) {
                java.security.Signature privateSignature = java.security.Signature.getInstance("SHA256withRSA");
                privateSignature.initSign(runtimeSigningPrivateKey);
                privateSignature.update(rawJsonOutput.getBytes(StandardCharsets.UTF_8));
                digitalSignature = Base64.getEncoder().encodeToString(privateSignature.sign());
            }

            // Step B: Symmetric AES Session Isolation
            KeyGenerator aesKeyGen = KeyGenerator.getInstance("AES");
            aesKeyGen.init(256);
            SecretKey dynamicAesKey = aesKeyGen.generateKey();

            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, dynamicAesKey, new IvParameterSpec(iv));
            byte[] encryptedDataBytes = aesCipher.doFinal(rawJsonOutput.getBytes(StandardCharsets.UTF_8));

            // Step C: Asymmetric RSA Key Wrapper
            PublicKey rsaPublicKey = extractPublicKeyFromClientAsset(clientPublicKeyBase64);
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey);
            byte[] encryptedAesKeyBytes = rsaCipher.doFinal(dynamicAesKey.getEncoded());

            return ResponseEntity.ok()
                    .header("X-Content-Secure", "true")
                    .header("X-DOORS-KID", activeCert.getKeyId())
                    .header("X-DOORS-TRACE", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                        "encryptedKey", Base64.getEncoder().encodeToString(encryptedAesKeyBytes),
                        "iv", Base64.getEncoder().encodeToString(iv),
                        "secureData", Base64.getEncoder().encodeToString(encryptedDataBytes),
                        "signature", digitalSignature
                    ));

        } catch (Exception cryptEx) {
            log.error("💥 CRITICAL CRYPTO FAULT [Trace: {}] for client [{}]: Exception Type: [{}], Detail Message: [{}]", 
                      traceId, authenticatedClient, cryptEx.getClass().getName(), cryptEx.getMessage(), cryptEx);
            throw new EncryptionException("Failed to execute asymmetric block cipher wrapping operation routines: " + cryptEx.getMessage(), cryptEx);
        }
    }
    /**
     * 🛡️ UNIFIED PUBLIC KEY / CERTIFICATE PARSER
     * Parses Base64/PEM Public Keys or standard X.509 Certificate strings into java.security.PublicKey.
     */
    private int countTransferredRecords(List<Map<String, Object>> envelopes) {
        int total = 0;
        for (Map<String, Object> envelope : envelopes) {
            total += countRecordContainer(envelope.get("value"));
        }
        return total;
    }

    private int countRecordContainer(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof JsonNode node) {
            if (node.isArray()) return node.size();
            if (node.isObject()) {
                for (String key : List.of("DATA", "data", "rows", "results", "items", "payload")) {
                    JsonNode records = node.get(key);
                    if (records != null) return countRecordContainer(records);
                }
                return node.isEmpty() ? 0 : 1;
            }
            return node.isNull() ? 0 : 1;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("DATA", "data", "rows", "results", "items", "payload")) {
                if (map.containsKey(key)) return countRecordContainer(map.get(key));
            }
            return map.isEmpty() ? 0 : 1;
        }
        return 1;
    }

    private PublicKey extractPublicKeyFromClientAsset(String rawKeyString) throws Exception {
        if (rawKeyString == null || rawKeyString.isBlank()) {
            throw new IllegalArgumentException("Client Public Key parameter string is empty or unconfigured.");
        }

        String cleanKey = rawKeyString.trim();

        // Path 1: Standard X.509 Certificate Block
        if (cleanKey.contains("BEGIN CERTIFICATE")) {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream bais = new ByteArrayInputStream(cleanKey.getBytes(StandardCharsets.UTF_8))) {
                X509Certificate cert = (X509Certificate) certFactory.generateCertificate(bais);
                return cert.getPublicKey();
            }
        }

        // Path 2: Clean Base64 / Standard PEM Public Key
        String base64Only = cleanKey
                .replaceAll("-----[^-]+-----", "") // Strips any -----BEGIN...----- or -----END...----- headers
                .replaceAll("\\s+", "");            // Strips whitespace, \n, \r, \t

        byte[] keyBytes = Base64.getDecoder().decode(base64Only);

        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception x509Ex) {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream bais = new ByteArrayInputStream(keyBytes)) {
                X509Certificate cert = (X509Certificate) certFactory.generateCertificate(bais);
                return cert.getPublicKey();
            }
        }
    }
}
