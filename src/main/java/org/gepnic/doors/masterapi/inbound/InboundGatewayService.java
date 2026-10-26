package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class InboundGatewayService {
    private final InboundProperties properties;private final InboundRegistry registry;private final AgentRepository agents;private final JdbcTemplate db;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots=new Semaphore(16);
    public InboundGatewayService(InboundProperties p,InboundRegistry r,AgentRepository a,JdbcTemplate db){properties=p;registry=r;agents=a;this.db=db;}
    public void validateVersions(String service,String instance,long client,Integer protocol,Integer contract){registry.validateVersions(registry.require(service,instance,client),protocol,contract);}
    public ResponseEntity<?> execute(ApiClient client,InboundProtocol.Submission s,String signature) throws Exception {
        return executeInternal(client,s,signature,false);
    }
    ResponseEntity<?> executeEncrypted(ApiClient client,InboundProtocol.Submission s)throws Exception{return executeInternal(client,s,null,true);}
    private ResponseEntity<?> executeInternal(ApiClient client,InboundProtocol.Submission s,String signature,boolean authenticated)throws Exception {
        if(!properties.isEnabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WRITE_DISABLED");
        InboundProtocol.validateEnvelope(s);

        if(client.getClientId()!=s.clientId() || !Boolean.TRUE.equals(client.getIsActive()))throw new SecurityException("Invalid client");
        var route=registry.require(s.service(),s.instanceId(),s.clientId());
        if(!authenticated)verifySignature(client,s,signature);
        var definition=(JsonWriteDefinition.Spec)registry.definition(route);
        if(definition==null)AgreementContract.validate(s.payload());else JsonWriteDefinition.validate(definition,s.payload());
        var agent=agents.findById(route.agent()).filter(a->Boolean.TRUE.equals(a.getIsActive())).orElseThrow(()->new SecurityException("Write agent is not active"));
        URI url=URI.create(configuredValue(properties.getAgentUrls(),route.agent()).orElseGet(()->
            properties.getAgentUrl()!=null&&!properties.getAgentUrl().isBlank()?properties.getAgentUrl():agent.getBaseUrl())+"/v1/agent/inbound/"+(definition==null?s.service():"dynamic"));
        boolean loopbackHttp=properties.isAllowLoopbackHttp()&&"http".equalsIgnoreCase(url.getScheme())&&url.getHost()!=null&&
            (url.getHost().equalsIgnoreCase("localhost")||url.getHost().equals("127.0.0.1")||url.getHost().equals("::1")||url.getHost().equals("0:0:0:0:0:0:0:1"));
        if((!"https".equalsIgnoreCase(url.getScheme())&&!loopbackHttp) || url.getUserInfo()!=null || url.getQuery()!=null || url.getFragment()!=null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WRITE_AGENT_REQUIRES_HTTPS");
        String transitSecret=configuredValue(properties.getAgentSecrets(),route.agent()).orElse(properties.getTransitSecret());
        byte[] body=InboundProtocol.JSON.writeValueAsBytes(definition==null?s:new JsonWriteDefinition.Transit(s,definition,registry.agentRouting(route)?WriteAgentControl.databaseTarget(agent,transitSecret):null,registry.maxVersions(route)));
        String mac=InboundProtocol.mac(transitSecret,body);
        if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"WRITE_BUSY");
        int status=504;String outcome="WRITE_RESULT_UNKNOWN";
        int agentStatus=-1;String contentType="not received";String stage="connecting to Agent";
        try {
            var request=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json")
                .header("X-DOORS-INBOUND-MAC",mac).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            agentStatus=response.statusCode();
            contentType=response.headers().firstValue("Content-Type").orElse("absent");
            stage="reading Agent response";
            byte[] bytes;try(var input=response.body()){bytes=input.readNBytes(65537);}
            if(bytes.length>65536)throw new java.io.IOException("Oversized Agent response");
            stage="parsing Agent JSON response";
            var result=parseAgentResponse(bytes);
            status=response.statusCode();
            stage="validating Agent receipt/status";
            if(status==200 || status==201){
                if(!s.requestId().toString().equals(result.path("requestId").asText()) || !s.instanceId().equals(result.path("instanceId").asText())
                    || !Set.of("STORED","UNCHANGED").contains(result.path("outcome").asText()))throw new java.io.IOException("Invalid Agent receipt");
                outcome=result.path("replayed").asBoolean()?"REPLAYED":result.path("outcome").asText();
                return ResponseEntity.status(status).body(result);
            }
            if(Set.of(400,401,403,404,409,413,422,429,503).contains(status)){
                outcome=result.path("code").asText("WRITE_REJECTED");
                if(!Set.of("VERSION_CONFLICT","IDEMPOTENCY_CONFLICT","TENDER_ACCESS_DENIED","INVALID_PAYLOAD","UNKNOWN_JSON_FIELD","WRITE_RESULT_UNKNOWN","VERSION_LIMIT_REACHED").contains(outcome))outcome="WRITE_REJECTED";
                return ResponseEntity.status(status).body(Map.of("code",outcome,"requestId",s.requestId(),"message",outcome.equals("VERSION_LIMIT_REACHED")?"Version limit reached for this record. Contact the DOORS Administrator.":outcome));
            }
            throw new java.io.IOException("Unexpected Agent response");
        } catch(java.io.IOException e){
            logUnknown(s.requestId(),route.agent(),url,stage,agentStatus,contentType,e);
            status=504;outcome="WRITE_RESULT_UNKNOWN";
            return ResponseEntity.status(504).body(Map.of("code",outcome,"requestId",s.requestId(),"message","Result unknown. Retry the same request ID and payload."));
        } finally {
            slots.release();
            try {db.update("INSERT INTO inbound_write_audit(request_id,client_id,service_code,instance_id,agent_id,outcome,http_status,payload_sha256) VALUES(?,?,?,?,?,?,?,?)",
                s.requestId(),s.clientId(),s.service(),s.instanceId(),route.agent(),outcome,status,InboundProtocol.sha(s.payload().getBytes(StandardCharsets.UTF_8)));}
            catch(Exception e){LoggerFactory.getLogger(getClass()).error("Inbound audit unavailable for request {}",s.requestId());}
        }
    }
    private static Optional<String> configuredValue(Map<String,String> values,String agentId){
        if(values==null||agentId==null)return Optional.empty();
        String exact=values.get(agentId);if(exact!=null&&!exact.isBlank())return Optional.of(exact);
        return values.entrySet().stream().filter(e->e.getKey()!=null&&e.getKey().equalsIgnoreCase(agentId))
            .map(Map.Entry::getValue).filter(v->v!=null&&!v.isBlank()).findFirst();
    }
    static com.fasterxml.jackson.databind.JsonNode parseAgentResponse(byte[] bytes)throws java.io.IOException {
        try {
            var result=InboundProtocol.JSON.readTree(bytes);
            if(result==null||!result.isObject())throw new java.io.IOException("Agent response is empty or is not a JSON object");
            return result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){
            // Parser messages may quote response content. Log the failure without that content.
            throw new java.io.IOException("Agent response is not valid JSON ("+e.getClass().getSimpleName()+"); response body omitted");
        }
    }
    static void logUnknown(UUID requestId,String agent,URI url,String stage,int status,String contentType,java.io.IOException failure){
        LoggerFactory.getLogger(InboundGatewayService.class).error(
            "WRITE_RESULT_UNKNOWN requestId={} agent={} endpoint={} stage={} agentHttpStatus={} contentType={}; retry the identical request ID and payload",
            requestId,agent,url,stage,status,contentType.replaceAll("[\\r\\n]"," "),failure);
    }
    static void verifySignature(ApiClient client,InboundProtocol.Submission s,String signature) throws Exception {
        long now=Instant.now().getEpochSecond();
        if(s.timestamp()<now-300 || s.timestamp()>now+60)throw new SecurityException("Write signature has expired");
        try {
            String pem=client.getClientPublicKey();if(pem==null)throw new SecurityException("Client public key is required");
            PublicKey key;
            if(pem.contains("BEGIN CERTIFICATE")){
                var cert=(java.security.cert.X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                cert.checkValidity();key=cert.getPublicKey();
            }else key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""))));
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);
            verifier.update(InboundProtocol.signingText(s).getBytes(StandardCharsets.UTF_8));
            if(!verifier.verify(Base64.getDecoder().decode(signature)))throw new SecurityException("Invalid write signature");
        }catch(Exception e){throw new SecurityException("Invalid or missing write signature/public key");}
    }
}
