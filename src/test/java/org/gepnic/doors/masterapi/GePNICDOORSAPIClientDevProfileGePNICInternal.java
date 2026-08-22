/**
 * ========================================================================================
 * 🛡️ NATIONAL INFORMATICS CENTRE (NIC) — GePNIC - Data Orchestration API Client 
 * 📑 STANDARD OPERATING PROCEDURE (SOP) FOR CRYPTOGRAPHIC ASSET MANAGEMENT
 * Disclaimer: This generated client template is provided as a reference implementation
 * to demonstrate secure integration practices with the DOORS API. It is the
 * responsibility of the implementer to ensure that all cryptographic materials 
 * are managed in accordance with organizational security policies and industry 
 * best practices.
 * ========================================================================================
 * * 1. IDENTITY & CONFIGURATION SUMMARY
 * - Client Class: GePNICDOORSAPIClientDevProfileGePNICInternal
 * - Profile Mode: DEVELOPMENT PROFILE (Ephemeral In-Memory)
 * - Security Model: Digital Certificate Handshake with Outbound Data Payload Encryption
 * * 2. KEY STORAGE MANDATE & CRITERIA (PRODUCTION RUNTIME)
 * - Ephemeral RSA generation is active. No local file footprint is required on disk for testing.
 * - Move to Production Profile before distributing integration assets to state data center environments.
 * - Ensure whitelisted network IPs align with the active local terminal boundaries.
 * * 3. KEY ROTATION & CHANGING DEFAULT STORAGE PATHS
 * - Default Test Path Location: System.getProperty("user.home") + "/security/doors-identity.p12"
 * - Custom Environment Variables Injection: To override hardcoded configuration schemas, modify your application instantiation
 * to inject configuration parameters natively from host environment variables:
 * e.g., String activeWalletPath = System.getenv("DOORS_WALLET_PATH");
 * String activeWalletPass = System.getenv("DOORS_WALLET_PASS");
 * String activeWalletAlias = System.getenv("DOORS_WALLET_ALIAS");
 * * And Change 'client.loadPermanentIdentity' in this code as -> client.loadPermanentIdentity(activeWalletPath, System.getenv("DOORS_WALLET_PASS"), System.getenv("DOORS_WALLET_ALIAS"));
 * * ====================================================================================
 */
package org.gepnic.doors.masterapi;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

public class GePNICDOORSAPIClientDevProfileGePNICInternal {

    private final String baseUrl;
    private final String apiKey;
    private KeyPair liveKeyPair;
    private String publicKeyBase64;

    private PublicKey cachedDoorsPublicKey = null;
    private String cachedDoorsKeyId = null;

    public GePNICDOORSAPIClientDevProfileGePNICInternal(String baseUrl, String apiKey) throws Exception {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        generateVolatileKeyPair();
    }

    private void generateVolatileKeyPair() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new java.security.SecureRandom());
        this.liveKeyPair = keyGen.generateKeyPair();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(this.liveKeyPair.getPublic().getEncoded());
    }

    public void executeHandshake() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/handshake").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-API-KEY", this.apiKey);
        conn.setDoOutput(true);

        String payload = String.format("{\"publicKey\":\"%s\"}", this.publicKeyBase64);
        try (OutputStream os = conn.getOutputStream()) { os.write(payload.getBytes(StandardCharsets.UTF_8)); }

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("DOORS-HANDSHAKE-REJECTED.");
        }
    }

    private synchronized PublicKey resolveDoorsPublicKey(String requiredKeyId) throws Exception {
        if (this.cachedDoorsPublicKey != null && requiredKeyId.equals(this.cachedDoorsKeyId)) {
            return this.cachedDoorsPublicKey;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/.well-known/jwks.json").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-API-KEY", this.apiKey);
        String jwksResponse = readStream(conn.getInputStream());
        
        String base64UrlModulus = extractJsonValue(jwksResponse, "n");
        byte[] modulusBytes = Base64.getUrlDecoder().decode(base64UrlModulus);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(extractJsonValue(jwksResponse, "e"));

        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(modulus, exponent);
        this.cachedDoorsPublicKey = KeyFactory.getInstance("RSA").generatePublic(rsaSpec);
        this.cachedDoorsKeyId = requiredKeyId;

        return this.cachedDoorsPublicKey;
    }

    public String fetchReportData(String queryUniqueName, List<String> targetAgentIds, String criteriaParams) throws Exception {
        String encodedQuery = URLEncoder.encode(queryUniqueName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/orchestrate/" + encodedQuery).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-API-KEY", this.apiKey);
        conn.setDoOutput(true);

        StringBuilder agentsJson = new StringBuilder();
        agentsJson.append("[");
        for (int i = 0; i < targetAgentIds.size(); i++) {
            agentsJson.append("\"").append(targetAgentIds.get(i).trim()).append("\"");
            if (i < targetAgentIds.size() - 1) {
                agentsJson.append(",");
            }
        }
        agentsJson.append("]");

        String cleanParams = (criteriaParams == null || criteriaParams.trim().isEmpty()) ? "{}" : criteriaParams.trim();
        String jsonRequestPayload = "{\"agentIds\":" + agentsJson.toString() + ",\"params\":" + cleanParams + "}";

        try (OutputStream os = conn.getOutputStream()) { os.write(jsonRequestPayload.getBytes(StandardCharsets.UTF_8)); }

        int status = conn.getResponseCode();
        if (status == 403) {
            executeHandshake();
            return fetchReportData(queryUniqueName, targetAgentIds, criteriaParams);
        }

        if (status >= 400) {
            throw new RuntimeException("DOORS Error -> " + readStream(conn.getErrorStream()));
        }

        String serverKeyIdHint = conn.getHeaderField("X-DOORS-KID");
        String response = readStream(conn.getInputStream());

        String secureDataBase64 = extractJsonValue(response, "secureData");
        String encryptedKeyBase64 = extractJsonValue(response, "encryptedKey");
        String ivBase64 = extractJsonValue(response, "iv");
        String signatureBase64 = extractJsonValue(response, "signature");

        if (secureDataBase64 == null) return response;

        // 🛰️ MODULE 1: INBOUND WIRE DATA VERIFICATION PANEL
        System.out.println("\n================================================================");
        System.out.println("🛰️  INBOUND WIRE DATA VERIFICATION (RAW ENCRYPTED BLOCKS)");
        System.out.println("================================================================");
        System.out.println("🔒 Encrypted Session AES Key : " + truncateSignatureString(encryptedKeyBase64));
        System.out.println("🔢 Initialization Vector (IV): " + ivBase64);
        System.out.println("📦 Ciphertext Payload Body   : " + truncateSignatureString(secureDataBase64));
        System.out.println("================================================================");

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, this.liveKeyPair.getPrivate());
        byte[] rawAesKeyBytes = rsaCipher.doFinal(Base64.getDecoder().decode(encryptedKeyBase64));

        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(rawAesKeyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(Base64.getDecoder().decode(ivBase64));
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        String decryptedReportJson = new String(aesCipher.doFinal(Base64.getDecoder().decode(secureDataBase64)), StandardCharsets.UTF_8);

        java.security.PublicKey doorsPublicKey = resolveDoorsPublicKey(serverKeyIdHint != null ? serverKeyIdHint : "doors-pki-v1");
        
        java.security.Signature publicSignature = java.security.Signature.getInstance("SHA256withRSA");
        publicSignature.initVerify(doorsPublicKey);
        publicSignature.update(decryptedReportJson.getBytes(StandardCharsets.UTF_8));

        if (!publicSignature.verify(Base64.getDecoder().decode(signatureBase64))) {
            throw new java.security.SignatureException("💥 CRYPTOGRAPHIC FAULT: Signature verification mismatch.");
        }

        // 🛡️ MODULE 2: DIGITAL SIGNATURE AUDIT TRAIL PANEL
        System.out.println("\n----------------------------------------------------------------");
        System.out.println("🛡️  DOORS PKI EMBEDDED DIGITAL SIGNATURE VERIFIED");
        System.out.println("📦 Key Identifier Hint (KID)  : " + (serverKeyIdHint != null ? serverKeyIdHint : "UNKNOWN"));
        System.out.println("🔑 Verification Engine State  : SHA256withRSA (Algorithm Validated)");
        System.out.println("✍️  Trunk Payload Signature    : " + truncateSignatureString(signatureBase64));
        System.out.println("----------------------------------------------------------------\n");

        return decryptedReportJson;
    }

    // 🚀 CLEAN TRUNCATION HELPER FOR LOG ALIGNMENT
    private static String truncateSignatureString(String sig) {
        if (sig == null || sig.length() <= 32) return sig;
        return sig.substring(0, 16) + "..." + sig.substring(sig.length() - 16);
    }

    private static String readStream(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line.trim());
        }
        return sb.toString();
    }

    private static String extractJsonValue(String jsonStr, String propertyKey) {
        if (!jsonStr.contains(propertyKey)) return null;
        try {
            int startIndex = jsonStr.indexOf("\"" + propertyKey + "\":") + propertyKey.length() + 3;
            if (jsonStr.charAt(startIndex) == '"') {
                startIndex++;
                return jsonStr.substring(startIndex, jsonStr.indexOf('"', startIndex));
            } else {
                int endIndex = jsonStr.indexOf(',', startIndex);
                if (endIndex == -1) endIndex = jsonStr.indexOf('}', startIndex);
                return jsonStr.substring(startIndex, endIndex).trim();
            }
        } catch (Exception e) { return null; }
    }

    public static void main(String[] args) {
        try {
            String configUrl = "http://localhost:8052/api/v1/master/gateway";
            String configKey = "obxXlht2wcZF9FDAG6i4EGxlyFMAXmxZ";
            
            List<String> assignedAgents = java.util.Arrays.asList("GePNIC-Assam");

            System.out.println("====== RUNNING GENERATED DOORS DEVELOPMENT CLIENT ======");
            GePNICDOORSAPIClientDevProfileGePNICInternal client = new GePNICDOORSAPIClientDevProfileGePNICInternal(configUrl, configKey);
            client.executeHandshake();

            // Execution context for template: Bidders List
            System.out.println("\n🛰️ Orchestrating dataset query: Bidders List...");
            java.util.Map<String, String> map_Bidders_List = new java.util.HashMap<>();
            StringBuilder jsonBuilder_Bidders_List = new StringBuilder("{");
            int count_Bidders_List = 0;
            for (java.util.Map.Entry<String, String> entry : map_Bidders_List.entrySet()) {
                jsonBuilder_Bidders_List.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                if (++count_Bidders_List < map_Bidders_List.size()) {
                    jsonBuilder_Bidders_List.append(",");
                }
            }
            jsonBuilder_Bidders_List.append("}");
            String params_Bidders_List = jsonBuilder_Bidders_List.toString();

            String output_Bidders_List = client.fetchReportData("Bidders List", assignedAgents, params_Bidders_List);
            System.out.println("🎉 Response Resolved Successfully -> \n" + output_Bidders_List);
      
            System.out.println("\n====== 🏁 ALL CONTEXTUAL DATA ORCHESTRATIONS PASSED PERFECTLY ======");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}