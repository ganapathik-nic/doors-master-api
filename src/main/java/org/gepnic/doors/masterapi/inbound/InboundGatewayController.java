package org.gepnic.doors.masterapi.inbound;
import jakarta.servlet.http.HttpServletRequest;
import org.gepnic.doors.inbound.InboundProtocol;
import org.gepnic.doors.inbound.JsonWriteDefinition;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.config.TrustedProxyConfiguration;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.nio.charset.*;
@RestController @RequestMapping("/api/v1/inbound")
public class InboundGatewayController {
    private final InboundGatewayService service;private final ApiClientRepository clients;private final InboundProperties properties;
    public InboundGatewayController(InboundGatewayService s,ApiClientRepository c,InboundProperties p){service=s;clients=c;properties=p;}
    @org.springframework.beans.factory.annotation.Autowired private WriteEncryptionKeys encryptionKeys;
    @PostMapping(value="/{dataset}",consumes="application/json")
    public ResponseEntity<?> write(@PathVariable String dataset,HttpServletRequest request) throws Exception {
        if(!properties.isEnabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WRITE_DISABLED");
        var client=clients.findByApiKey(request.getHeader("X-API-KEY")).filter(c->Boolean.TRUE.equals(c.getIsActive())).orElseThrow(()->new SecurityException("Invalid client"));
        boolean encrypted=WriteCrypto.MODE.equals(request.getHeader("X-DOORS-Encryption"));
        if(request.getHeader("X-DOORS-Encryption")!=null&&!encrypted)throw new IllegalArgumentException("Unsupported encryption");
        int limit=encrypted?WriteCrypto.MAX_WIRE:InboundProtocol.MAX_BODY;
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"PAYLOAD_TOO_LARGE");
        String payload=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
        var s=new InboundProtocol.Submission(dataset,header(request,"X-DOORS-Instance-ID"),client.getClientId(),
            UUID.fromString(header(request,"Idempotency-Key")),Integer.parseInt(header(request,"X-DOORS-Schema-Version")),
            Long.parseLong(header(request,"X-DOORS-Expected-Version")),Long.parseLong(header(request,"X-DOORS-Timestamp")),payload);
        String protocol=request.getHeader("X-DOORS-Protocol-Version"),contract=request.getHeader("X-DOORS-Contract-Version");
        // Older signed clients omit these headers; their existing mapping remains pinned.
        if(protocol!=null||contract!=null)service.validateVersions(dataset,s.instanceId(),client.getClientId(),protocol==null?null:Integer.valueOf(protocol),contract==null?null:Integer.valueOf(contract));
        String clientIp=TrustedProxyConfiguration.clientIp(request);
        if(!encrypted)return service.execute(client,s,header(request,"X-DOORS-Signature"),clientIp);
        try {
            long now=java.time.Instant.now().getEpochSecond();if(s.timestamp()<now-300||s.timestamp()>now+60)throw new java.security.GeneralSecurityException();
            if(contract==null||protocol==null||!"1".equals(protocol))throw new java.security.GeneralSecurityException();
            java.security.PublicKey publicKey;
            String pem=client.getClientPublicKey();
            if(pem.contains("BEGIN CERTIFICATE")){var cert=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));cert.checkValidity();publicKey=cert.getPublicKey();}else publicKey=WriteCrypto.publicKey(pem);
            WriteCrypto.verify(WriteCrypto.requestText(dataset,s.instanceId(),s.requestId().toString(),s.schemaVersion(),s.expectedVersion(),s.timestamp(),Integer.parseInt(contract),payload),header(request,"X-DOORS-Signature"),publicKey);
            String kid=WriteCrypto.envelope(payload).get("kid");var serverKey=encryptionKeys.privateKey(kid);
            String plain=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(WriteCrypto.open(payload,serverKey))).toString();
            s=new InboundProtocol.Submission(dataset,s.instanceId(),s.clientId(),s.requestId(),s.schemaVersion(),s.expectedVersion(),s.timestamp(),plain);
            var result=service.executeEncrypted(client,s,clientIp);
            String response=WriteCrypto.seal(InboundProtocol.JSON.writeValueAsBytes(result.getBody()),publicKey,kid);
            return ResponseEntity.status(result.getStatusCode()).header("X-DOORS-Encryption",WriteCrypto.MODE).header("X-DOORS-Response-Signature",WriteCrypto.sign(WriteCrypto.responseText(s.requestId().toString(),result.getStatusCode().value(),response),serverKey)).contentType(MediaType.APPLICATION_JSON).body(response);
        }catch(java.security.GeneralSecurityException e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_ENCRYPTED_REQUEST","message","Encrypted request could not be verified"));}
    }
    private static String header(HttpServletRequest request,String name){
        String value=request.getHeader(name);
        if(value==null || value.isBlank() || value.length()>2048)throw new IllegalArgumentException("Missing or invalid write header");
        return value;
    }
    @ExceptionHandler(JsonWriteDefinition.UnknownJsonFieldsException.class)
    ResponseEntity<?> unknownFields(JsonWriteDefinition.UnknownJsonFieldsException e){return ResponseEntity.unprocessableEntity().body(Map.of("code","UNKNOWN_JSON_FIELD","message","The request contains fields that are not part of the approved JSON contract.","unknownFields",e.paths()));}
    @ExceptionHandler({IllegalArgumentException.class,CharacterCodingException.class})
    ResponseEntity<?> invalid(Exception e){return ResponseEntity.unprocessableEntity().body(Map.of("code","INVALID_WRITE_REQUEST","message","Check JSON and required write headers"));}
}
