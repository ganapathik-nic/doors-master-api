package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.*;
import javax.net.ssl.*;
import java.security.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfEnvironmentVariable(named="DOORS_INBOUND_E2E_AGENT",matches="https://.+")
class WriteGovernanceAgentIntegrationTest {
 @Test void approvedSandboxContractFlowsThroughRealHttpsAgentToVersionedStorage()throws Exception{
  SSLContext old=SSLContext.getDefault();
  try{
   KeyStore trust=KeyStore.getInstance("PKCS12");
   try(var in=Files.newInputStream(Path.of(System.getenv("DOORS_INBOUND_E2E_TRUST")))){trust.load(in,"test-password".toCharArray());}
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
   var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);SSLContext.setDefault(ssl);
   var f=new WriteGovernancePostgresTest();f.setup();f.body=f.body.replace("AGR/TEST","AGR/"+UUID.randomUUID());
   var p=new InboundProperties();p.setEnabled(true);p.getAgentSecrets().put("governance-test","integration-test-secret-32-characters-long");
   var agents=mock(AgentRepository.class);var agent=new Agent();agent.setIsActive(true);agent.setIsSandbox(true);agent.setBaseUrl("http://127.0.0.1:8051/dev-01");p.getAgentUrls().put("governance-test",System.getenv("DOORS_INBOUND_E2E_AGENT"));
   when(agents.findById("governance-test")).thenReturn(Optional.of(agent));
   f.service=new WriteGovernanceService(f.db,new WriteAgentControl(agents,p));
   long contract=f.draft();f.test(contract);f.submit(contract);f.review(contract,"bob",true);
   long deployment=f.transaction(()->f.service.deploy(new WriteGovernanceService.Destination(contract,"integration","governance-test"),"bob"));
   f.transaction(()->{try{return f.service.verify(deployment,"bob");}catch(Exception e){throw new RuntimeException(e);}});
   long clientId=System.currentTimeMillis();
   f.db.update("INSERT INTO external_api_clients(client_id) VALUES(?)",clientId);
   f.db.update("INSERT INTO doors_inbound.tender_write_grant VALUES('integration',?,'1001',true)",clientId);
   var pair=KeyPairGenerator.getInstance("RSA").generateKeyPair();var client=new ApiClient();client.setClientId(clientId);client.setIsActive(true);
   client.setClientPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
   var clients=mock(ApiClientRepository.class);when(clients.findById(clientId)).thenReturn(Optional.of(client));
   var registry=new InboundRegistry(f.db,agents,clients);
   f.transaction(()->{registry.register(new InboundRegistry.Registration("agreements","integration","governance-test",clientId,deployment),"bob");return null;});
   long route=f.db.queryForObject("SELECT id FROM inbound_write_routes WHERE client_id=?",Long.class,clientId);
   var gateway=new InboundGatewayService(p,registry,agents,f.db);
   var request=new InboundProtocol.Submission("agreements","integration",clientId,UUID.randomUUID(),1,0,Instant.now().getEpochSecond(),f.body);
   assertThatThrownBy(()->gateway.execute(client,request,sign(pair,request))).isInstanceOf(SecurityException.class);
   registry.enable(route,true);
   assertThat(gateway.execute(client,request,sign(pair,request)).getStatusCode().value()).isEqualTo(201);
   assertThat(gateway.execute(client,request,sign(pair,request)).getStatusCode().value()).isEqualTo(200);
   registry.enable(route,false);
   assertThatThrownBy(()->gateway.execute(client,request,sign(pair,request))).isInstanceOf(SecurityException.class);
  }finally{SSLContext.setDefault(old);}
 }
 String sign(KeyPair pair,InboundProtocol.Submission s)throws Exception{
  var sig=Signature.getInstance("SHA256withRSA");sig.initSign(pair.getPrivate());sig.update(InboundProtocol.signingText(s).getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(sig.sign());
 }
}
