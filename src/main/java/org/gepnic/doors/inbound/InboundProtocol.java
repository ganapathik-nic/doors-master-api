package org.gepnic.doors.inbound;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Wire contract shared by the write gateway and Agent; independent of read protocols. */
public final class InboundProtocol {
    private InboundProtocol() {}
    public static final int MAX_BODY = 1024 * 1024;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
            .maxStringLength(MAX_BODY).maxNumberLength(100).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Submission(String service, String instanceId, long clientId, UUID requestId,
        int schemaVersion, long expectedVersion, long timestamp, String payload) {}
    public static String sha(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String signingText(Submission s) {
        return "DOORS-INBOUND-1\n"+s.service()+"\n"+s.instanceId()+"\n"+s.requestId()+"\n"
            +s.schemaVersion()+"\n"+s.expectedVersion()+"\n"+s.timestamp()+"\n"
            +sha(s.payload().getBytes(StandardCharsets.UTF_8));
    }
    public static String fingerprint(Submission s) {
        // Timestamp is transport freshness, not business identity; retries may be freshly signed.
        String value="DOORS-INBOUND-1\n"+s.clientId()+"\n"+s.service()+"\n"+s.instanceId()+"\n"
            +s.requestId()+"\n"+s.schemaVersion()+"\n"+s.expectedVersion()+"\n"
            +sha(s.payload().getBytes(StandardCharsets.UTF_8));
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String mac(String secret, byte[] body) {
        if (secret == null || secret.length()<32) throw new IllegalStateException("Inbound transit secret is not provisioned");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static boolean matchesMac(String expected,String supplied) {
        try { return MessageDigest.isEqual(Base64.getDecoder().decode(expected),Base64.getDecoder().decode(supplied)); }
        catch(Exception e) { return false; }
    }
    public static void validateEnvelope(Submission s) {
        if(s==null || s.requestId()==null || s.clientId()<1 || s.expectedVersion()<0
            || s.expectedVersion()==Long.MAX_VALUE || s.schemaVersion()!=1
            || s.instanceId()==null || !s.instanceId().matches("[A-Za-z0-9_-]{1,80}")
            || s.service()==null || !s.service().matches("[a-z][a-z-]{0,39}")
            || s.payload()==null || s.payload().getBytes(StandardCharsets.UTF_8).length>MAX_BODY)
            throw new IllegalArgumentException("Invalid inbound request metadata or size");
    }
}
