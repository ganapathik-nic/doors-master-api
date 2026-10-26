package org.gepnic.doors.masterapi.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import javax.net.ssl.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: real HTTPS Agent and isolated PostgreSQL; never uses the application registry. */
@EnabledIfEnvironmentVariable(named="DOORS_AGREEMENT_SAMPLE", matches=".+")
class AgreementCompleteFlowTest {
 @Test void agreementApprovalProvisioningSignedWritesAndVersions() throws Exception {
  assertThat(System.getenv("DOORS_INBOUND_TEST_JDBC")).isEqualTo("jdbc:postgresql://127.0.0.1:55439/postgres");
  assertThat(System.getenv("DOORS_INBOUND_E2E_AGENT")).isEqualTo("https://localhost:55441");
  SSLContext previous=SSLContext.getDefault();
  try {
   var trust=KeyStore.getInstance("PKCS12");
   try(var input=Files.newInputStream(Path.of(System.getenv("DOORS_INBOUND_E2E_TRUST")))){trust.load(input,"test-password".toCharArray());}
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
   var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);SSLContext.setDefault(ssl);
   String sample=Files.readString(Path.of(System.getenv("DOORS_AGREEMENT_SAMPLE")));
   var document=InboundProtocol.JSON.readTree(sample);
   var types=new LinkedHashMap<String,String>();
   document.fieldNames().forEachRemaining(name->{if(name.contains("date"))types.put("/"+name,"date");});
   String code="agreement-test-"+UUID.randomUUID().toString().substring(0,8).replaceAll("[0-9]","a");
   var selected=JsonWriteDefinition.extractableFields(sample).stream().map(JsonWriteDefinition.Field::path).toList();
   var spec=JsonWriteDefinition.createSelected(code,List.of("/tender_id","/agreement_no"),sample,types,Map.of(),selected);
   assertThat(spec.fields()).extracting(JsonWriteDefinition.Field::column).contains("party_1_name","party_1_type","party_2_name").doesNotContain("party_1","party_2");
   var f=new WriteGovernancePostgresTest();f.setup();
   f.db.update("INSERT INTO agents(agent_id,is_active) VALUES('Dev-01',true) ON CONFLICT(agent_id) DO NOTHING");
   var properties=new InboundProperties();properties.setEnabled(true);
   properties.getAgentUrls().put("Dev-01",System.getenv("DOORS_INBOUND_E2E_AGENT"));
   properties.getAgentSecrets().put("Dev-01","integration-test-secret-32-characters-long");
   var agents=mock(AgentRepository.class);var agent=new Agent();agent.setIsActive(true);agent.setIsSandbox(true);
   agent.setBaseUrl("http://127.0.0.1:8051/dev-01");when(agents.findById("Dev-01")).thenReturn(Optional.of(agent));
   var service=new WriteGovernanceService(f.db,new WriteAgentControl(agents,properties));
   long id=f.transaction(()->service.create(new WriteGovernanceService.Proposal("Agreement complete-flow test","Isolated acceptance test",sample,null,code,spec.businessKey(),types,Map.of(),selected),new UsernamePasswordAuthenticationToken("agreement-test-author","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));
   var proof=f.transaction(()->{try{return service.test(id,"Dev-01","agreement-test-author");}catch(Exception e){throw new RuntimeException(e);}});
   assertThat(proof).containsEntry("success",true).containsEntry("rolledBack",true);
   f.transaction(()->{service.submit(id,"agreement-test-author");return null;});
   assertThatThrownBy(()->f.transaction(()->{service.reviewCurrent(id,"agreement-test-author",true,"Self review",JsonWriteDefinition.hash(spec));return null;})).isInstanceOf(SecurityException.class);
   f.transaction(()->{service.reviewCurrent(id,"agreement-test-reviewer",true,"Verified sample and generated SQL",JsonWriteDefinition.hash(spec));return null;});
   long deployment=f.transaction(()->service.deploy(new WriteGovernanceService.Destination(id,"agreement_e2e","Dev-01"),"agreement-test-reviewer"));
   String ns=JsonWriteDefinition.schema(spec),table=JsonWriteDefinition.table(spec);
   assertThat(f.db.queryForObject("SELECT to_regclass(?)::text",String.class,ns+"."+table)).isNull();
   // DBA step: apply the approved DDL explicitly, outside the runtime API.
   f.db.execute(JsonWriteDefinition.ddl(spec));
   long clientId=System.currentTimeMillis();
   try {
    f.db.execute("GRANT USAGE ON SCHEMA "+ns+" TO inbound_test_writer");
    f.db.execute("GRANT EXECUTE ON FUNCTION "+ns+"."+JsonWriteDefinition.object(spec,"store_document")+"(text,bigint,uuid,bigint,text,json,text) TO inbound_test_writer");
    f.transaction(()->{try{return service.verify(deployment,"agreement-test-reviewer");}catch(Exception e){throw new RuntimeException(e);}});
    assertThat(f.db.queryForObject("SELECT count(*) FROM "+ns+"."+table,Integer.class)).isZero();
    f.db.update("INSERT INTO external_api_clients(client_id) VALUES(?)",clientId);
    var pair=KeyPairGenerator.getInstance("RSA").generateKeyPair();var client=new ApiClient();client.setClientId(clientId);client.setIsActive(true);client.setClientPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    var clients=mock(ApiClientRepository.class);when(clients.findById(clientId)).thenReturn(Optional.of(client));
    var registry=new InboundRegistry(f.db,agents,clients);
    f.transaction(()->{registry.register(new InboundRegistry.Registration(code,"agreement_e2e","Dev-01",clientId,deployment),"agreement-test-reviewer");return null;});
    long route=f.db.queryForObject("SELECT id FROM inbound_write_routes WHERE client_id=?",Long.class,clientId);
    var gateway=new InboundGatewayService(properties,registry,agents,f.db);
    var request=request(code,clientId,UUID.randomUUID(),0,sample);
    assertThatThrownBy(()->gateway.execute(client,request,sign(pair,request))).isInstanceOf(SecurityException.class);
    registry.enable(route,true);
    assertThatThrownBy(()->gateway.execute(client,request,"invalid")).isInstanceOf(SecurityException.class);
    assertThat(gateway.execute(client,request,sign(pair,request)).getStatusCode().value()).isEqualTo(403);
    String key=InboundProtocol.JSON.writeValueAsString(List.of(document.path("tender_id").asText(),document.path("agreement_no").asText()));
    f.db.update("INSERT INTO "+ns+"."+JsonWriteDefinition.object(spec,"service_grant")+" VALUES('agreement_e2e',?,true)",clientId);
    var first=gateway.execute(client,request,sign(pair,request));assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(InboundProtocol.JSON.valueToTree(first.getBody()).path("recordVersion").asInt()).isEqualTo(1);
    assertThat(gateway.execute(client,request,sign(pair,request)).getStatusCode().value()).isEqualTo(200);
    var changed=document.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("tender_name",document.path("tender_name").asText()+" - version test");
    String next=InboundProtocol.JSON.writeValueAsString(changed);
    var collision=request(code,clientId,request.requestId(),0,next);
    assertThat(gateway.execute(client,collision,sign(pair,collision)).getStatusCode().value()).isEqualTo(409);
    var stale=request(code,clientId,UUID.randomUUID(),0,next);
    assertThat(gateway.execute(client,stale,sign(pair,stale)).getStatusCode().value()).isEqualTo(409);
    var second=request(code,clientId,UUID.randomUUID(),1,next);
    assertThat(gateway.execute(client,second,sign(pair,second)).getStatusCode().value()).isEqualTo(201);
    assertThat(f.db.queryForObject("SELECT count(*) FROM "+ns+"."+table,Integer.class)).isEqualTo(2);
    assertThat(f.db.queryForObject("SELECT payload::text FROM "+ns+"."+table+" WHERE record_version=1",String.class)).isEqualTo(sample);
    assertThat(f.db.queryForObject("SELECT payload::text FROM "+ns+"."+JsonWriteDefinition.object(spec,"latest"),String.class)).isEqualTo(next);
    assertThat(f.db.queryForObject("SELECT party_1_name FROM "+ns+"."+table+" WHERE record_version=1",String.class)).isEqualTo(document.path("party_1").path("name").asText());
    assertThat(f.db.queryForObject("SELECT agreement_date::text FROM "+ns+"."+table+" WHERE record_version=1",String.class)).isEqualTo(document.path("agreement_date").asText());
    registry.enable(route,false);
    assertThatThrownBy(()->gateway.execute(client,second,sign(pair,second))).isInstanceOf(SecurityException.class);
    System.out.println("AGREEMENT_COMPLETE_FLOW: sandbox rollback, independent approval, DBA provisioning, verification, signed write 201, replay 200, conflicts 409, immutable versions 1/2, readable columns, original JSON preserved, route disabled: PASS");
   } finally {
    f.db.update("DELETE FROM inbound_write_routes WHERE client_id=?",clientId);
    f.db.update("DELETE FROM inbound_write_audit WHERE client_id=?",clientId);
    f.db.update("DELETE FROM external_api_clients WHERE client_id=?",clientId);
    f.db.execute("DROP FUNCTION "+ns+"."+JsonWriteDefinition.object(spec,"store_document")+"(text,bigint,uuid,bigint,text,json,text)");
    f.db.execute("DROP VIEW "+ns+"."+JsonWriteDefinition.object(spec,"latest"));
    for(String object:List.of(table,JsonWriteDefinition.object(spec,"head"),JsonWriteDefinition.object(spec,"receipt"),JsonWriteDefinition.object(spec,"service_grant")))f.db.execute("DROP TABLE "+ns+"."+object);
    f.db.update("DELETE FROM write_deployments WHERE id=?",deployment);
    f.db.update("DELETE FROM write_governance_events WHERE contract_id=?",id);
    f.db.update("DELETE FROM write_contracts WHERE id=?",id);
   }
  } finally {SSLContext.setDefault(previous);}
 }
 private InboundProtocol.Submission request(String service,long client,UUID id,long expected,String payload){return new InboundProtocol.Submission(service,"agreement_e2e",client,id,1,expected,Instant.now().getEpochSecond(),payload);}
 private String sign(KeyPair pair,InboundProtocol.Submission request)throws Exception{var signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(InboundProtocol.signingText(request).getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(signature.sign());}
}
