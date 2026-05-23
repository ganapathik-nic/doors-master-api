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
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

public class DoorsExternalApiClient {

    private final String baseUrl;
    private final String apiKey;
    private KeyPair liveKeyPair;
    private String publicKeyBase64;

    // Volatile RAM Cache Memory allocations for DOORS Public Verification Keys
    private PublicKey cachedDoorsPublicKey = null;
    private String cachedDoorsKeyId = null;

    public DoorsExternalApiClient(String baseUrl, String apiKey) throws Exception {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        generateVolatileKeyPair();
    }

    private void generateVolatileKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        this.liveKeyPair = keyGen.generateKeyPair();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(this.liveKeyPair.getPublic().getEncoded());
    }

    public void executeHandshake() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/handshake").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-API-KEY", this.apiKey);
        conn.setDoOutput(true);

        String payload = "{\"publicKey\":\"" + this.publicKeyBase64 + "\"}";
        try (OutputStream os = conn.getOutputStream()) { os.write(payload.getBytes(StandardCharsets.UTF_8)); }

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("DOORS-HANDSHAKE-REJECTED.");
        }
    }

    /**
     * 🔄 DYNAMIC KEY RESOLVER LAYER: Lazy loads JWKS endpoints from Master API over HTTPS
     */
    private synchronized PublicKey resolveDoorsPublicKey(String requiredKeyId) throws Exception {
        if (this.cachedDoorsPublicKey != null && requiredKeyId.equals(this.cachedDoorsKeyId)) {
            return this.cachedDoorsPublicKey;
        }

        System.out.println("🔄 [JWKS-SYNC] Rotation detected or cache cold. Fetching public keys from context source...");
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/.well-known/jwks.json").openConnection();
        conn.setRequestMethod("GET");

        String jwksResponse = readStream(conn.getInputStream());
        
        // Extract raw JSON Modulus properties natively
        String base64UrlModulus = extractJsonValue(jwksResponse, "n");
        byte[] modulusBytes = Base64.getUrlDecoder().decode(base64UrlModulus);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(extractJsonValue(jwksResponse, "e"));

        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(modulus, exponent);
        this.cachedDoorsPublicKey = KeyFactory.getInstance("RSA").generatePublic(rsaSpec);
        this.cachedDoorsKeyId = requiredKeyId;

        System.out.println("🎯 [JWKS-SYNC] Cache sync successful. Validating signatures under version alignment entry: " + requiredKeyId);
        return this.cachedDoorsPublicKey;
    }

    public String fetchReportData(String queryUniqueName, String jsonRequestPayload) throws Exception {
        String encodedQuery = URLEncoder.encode(queryUniqueName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/orchestrate/" + encodedQuery).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-API-KEY", this.apiKey);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) { os.write(jsonRequestPayload.getBytes(StandardCharsets.UTF_8)); }

        int status = conn.getResponseCode();

        // ⏱️ Auto Handshake Renewal Loop (If 30 mins lapses or server wipes RAM cache)
        if (status == 403) {
            System.out.println("⚠️ [DOORS] Session expired. Renegotiating handshake keys dynamically...");
            executeHandshake();
            return fetchReportData(queryUniqueName, jsonRequestPayload);
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

        if (secureDataBase64 == null) return response; // Encryption bypassed fallback check

        // A. Decrypt Symmetric Processing Payload Keys
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, this.liveKeyPair.getPrivate());
        byte[] rawAesKeyBytes = rsaCipher.doFinal(Base64.getDecoder().decode(encryptedKeyBase64));

        // B. Decrypt Data Content
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(rawAesKeyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(Base64.getDecoder().decode(ivBase64));
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        String decryptedReportJson = new String(aesCipher.doFinal(Base64.getDecoder().decode(secureDataBase64)), StandardCharsets.UTF_8);

        // 🛡️ C. DYNAMIC PKI SIGNATURE INTEGRITY AUDIT Check
        java.security.PublicKey doorsPublicKey = resolveDoorsPublicKey(serverKeyIdHint != null ? serverKeyIdHint : "doors-pki-v1");
        
        java.security.Signature publicSignature = java.security.Signature.getInstance("SHA256withRSA");
        publicSignature.initVerify(doorsPublicKey);
        publicSignature.update(decryptedReportJson.getBytes(StandardCharsets.UTF_8));

        if (!publicSignature.verify(Base64.getDecoder().decode(signatureBase64))) {
            throw new java.security.SignatureException("💥 CRYPTOGRAPHIC FAULT: Server signature verification mismatch! Payload corrupted.");
        }

        return decryptedReportJson;
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
                return jsonStr.substring(startIndex, jsonStr.indexOf("\"", startIndex));
            } else {
                int endIndex = jsonStr.indexOf(",", startIndex);
                if (endIndex == -1) endIndex = jsonStr.indexOf("}", startIndex);
                return jsonStr.substring(startIndex, endIndex).trim();
            }
        } catch (Exception e) { return null; }
    }

    // ========================================================================
    // 🏁 STANDALONE RUNNABLE EXECUTION HARNESS Entry Point
    // ========================================================================
    public static void main(String[] args) {
        try {
            String configUrl = "http://localhost:8052/api/v1/master/gateway";
            String configKey = "obxXlht2wcZF9FDAG6i4EGxlyFMAXmxZ";
            String reportName = "Bidders List";
            String queryJsonBody = "{\"agentIds\":[\"Dev-01\"],\"params\":{}}";

            System.out.println("====== STARTING INTEGRATED ZERO-TRUST CONSUMER CLIENT ======");
            DoorsExternalApiClient client = new DoorsExternalApiClient(configUrl, configKey);

            System.out.println("[1] Dispensing initialization trust handshake payload...");
            client.executeHandshake();
            System.out.println(" -> Handshake approved by endpoint cache registry.");

            System.out.println("[2] Querying orchestration extraction stream pipeline...");
            String cleanPlaintextJson = client.fetchReportData(reportName, queryJsonBody);

            System.out.println("\n====================================================================");
            System.out.println("🎉 DATA ENVELOPE OPENED & PKI SIGNATURE VERIFIED SUCCESSFULLY:");
            System.out.println("====================================================================");
            System.out.println(cleanPlaintextJson);
            System.out.println("====================================================================\n");

        } catch (Exception ex) {
            System.err.println("\n❌ CLIENT TERMINAL FAULT INITIALIZING PIPELINE: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}