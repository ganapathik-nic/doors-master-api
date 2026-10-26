package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
@Service
public class WriteAgentControl {
 private final AgentRepository agents; private final InboundProperties properties;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 public WriteAgentControl(AgentRepository a,InboundProperties p){agents=a;properties=p;}
 public Map<String,Object> call(String agentId,String operation,String instance,String sample) throws Exception {
  return send(agentId,operation,instance,sample,null,false);
 }
 public Map<String,Object> callDynamic(String agentId,String operation,String instance,String sample,JsonWriteDefinition.Spec spec)throws Exception{return send(agentId,operation,instance,sample,spec,false);}
 public Map<String,Object> callAgentMapped(String agentId,String operation,String instance,JsonWriteDefinition.Spec spec)throws Exception{return send(agentId,operation,instance,null,spec,true);}
 public static JsonWriteDefinition.DatabaseTarget databaseTarget(org.gepnic.doors.masterapi.model.Agent a,String secret)throws Exception{
  if(a.getTargetDbHost()==null||!a.getTargetDbHost().matches("[A-Za-z0-9.-]+")||a.getTargetDbPort()==null||a.getTargetDbPort()<1||a.getTargetDbPort()>65535||a.getTargetDbName()==null||!a.getTargetDbName().matches("[A-Za-z0-9_-]+")||!"doors".equals(a.getTargetDbUser()))throw new IllegalArgumentException("Configure this Agent's database host, port, name and restricted doors account in Remote Agents");
  String password=a.getTargetDbPassword();
  return new JsonWriteDefinition.DatabaseTarget(a.getTargetDbHost(),a.getTargetDbPort(),a.getTargetDbName(),a.getTargetDbUser(),password==null||password.isBlank()?"":org.gepnic.doors.masterapi.util.EncryptionUtils.encrypt(password,secret));
 }
 private Map<String,Object> send(String agentId,String operation,String instance,String sample,JsonWriteDefinition.Spec spec,boolean agentRouting)throws Exception{
  String expectedHash=spec==null?AgreementDefinition.hash():JsonWriteDefinition.hash(spec);
  var agent=agents.findById(agentId).filter(a->Boolean.TRUE.equals(a.getIsActive())).orElseThrow(()->new IllegalArgumentException("Active Agent required"));
  if(operation.equals("sandbox")&&!Boolean.TRUE.equals(agent.getIsSandbox()))throw new SecurityException("Select an Agent explicitly registered as a sandbox");
  URI uri=URI.create(properties.getAgentUrls().getOrDefault(agentId,agent.getBaseUrl())+"/v1/agent/inbound/governance/"+operation);
  if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Agent requires HTTPS");
  byte[] body=InboundProtocol.JSON.writeValueAsBytes(spec==null?new AgreementDefinition.Control(operation,instance,sample,Instant.now().getEpochSecond(),expectedHash):new JsonWriteDefinition.Control(operation,instance,sample,Instant.now().getEpochSecond(),spec,agentRouting?databaseTarget(agent,properties.getAgentSecrets().get(agentId)):null));
  var req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json")
   .header("X-DOORS-INBOUND-MAC",InboundProtocol.mac(properties.getAgentSecrets().get(agentId),body))
   .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
  var res=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
  byte[] bytes;try(var in=res.body()){bytes=in.readNBytes(2*InboundProtocol.MAX_BODY+1);}
  if(res.statusCode()!=200||bytes.length>2*InboundProtocol.MAX_BODY)throw new IllegalStateException("Agent "+operation+" failed (HTTP "+res.statusCode()+"). Check the Agent profile and provisioning.");
  Map<String,Object> result=InboundProtocol.JSON.readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<>(){});
  if(!expectedHash.equals(result.get("definitionHash")) || !operation.equals(result.get("operation"))
    || !Boolean.TRUE.equals(result.get("success")))throw new IllegalStateException("Agent definition/evidence mismatch");
  return result;
 }
}
