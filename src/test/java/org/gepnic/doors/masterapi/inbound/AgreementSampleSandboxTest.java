package org.gepnic.doors.masterapi.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in test against the local HTTPS sandbox; never approves or provisions a destination. */
@EnabledIfEnvironmentVariable(named="DOORS_AGREEMENT_SAMPLE", matches=".+")
class AgreementSampleSandboxTest {
 @Test void agreementDateAndJsonRoundTripThroughHttpsSandbox() throws Exception {
  SSLContext previous=SSLContext.getDefault();
  try {
   KeyStore trust=KeyStore.getInstance("PKCS12");
   try(var input=Files.newInputStream(Path.of(System.getenv("DOORS_INBOUND_E2E_TRUST")))) {trust.load(input,"test-password".toCharArray());}
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
   var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);SSLContext.setDefault(ssl);
   String sample=Files.readString(Path.of(System.getenv("DOORS_AGREEMENT_SAMPLE")));
   var data=InboundProtocol.JSON.readTree(sample);
   Map<String,String> types=new LinkedHashMap<>();
   data.fieldNames().forEachRemaining(name->{if(name.contains("date"))types.put("/"+name,"date");});

   var fixture=new WriteGovernancePostgresTest();fixture.setup();
   var properties=new InboundProperties();properties.setEnabled(true);
   properties.getAgentUrls().put("Dev-01",System.getenv("DOORS_INBOUND_E2E_AGENT"));
   properties.getAgentSecrets().put("Dev-01","integration-test-secret-32-characters-long");
   var agents=mock(AgentRepository.class);var agent=new Agent();agent.setIsActive(true);agent.setIsSandbox(true);
   agent.setBaseUrl("http://127.0.0.1:8051/dev-01");when(agents.findById("Dev-01")).thenReturn(Optional.of(agent));
   var service=new WriteGovernanceService(fixture.db,new WriteAgentControl(agents,properties));
   var selected=JsonWriteDefinition.extractableFields(sample).stream().map(JsonWriteDefinition.Field::path).filter(path->!path.equals("/tender_name")).toList();
   var spec=JsonWriteDefinition.createSelected("agreement-data",List.of("/tender_id","/agreement_no"),sample,types,Map.of("/party_1/name","contractor_name"),selected);
   service.validateTypes(spec,sample);
   assertThat(spec.fields()).extracting(JsonWriteDefinition.Field::path).doesNotContain("/party_1","/party_2","/tender_name");
   assertThat(spec.fields()).contains(new JsonWriteDefinition.Field("/party_1/name","contractor_name","text"),new JsonWriteDefinition.Field("/party_2/name","party_2_name","text"));
   long id=fixture.transaction(()->service.create(new WriteGovernanceService.Proposal("Agreement Date JSON sandbox test","Validate sample types without approval",sample,null,"agreement-data",spec.businessKey(),types,Map.of("/party_1/name","contractor_name"),selected),new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));
   var proof=fixture.transaction(()->{try{return service.test(id,"Dev-01","alice");}catch(Exception e){throw new RuntimeException(e);}});
   assertThat(proof).containsEntry("success",true).containsEntry("rolledBack",true);
   com.fasterxml.jackson.databind.JsonNode readback=InboundProtocol.JSON.valueToTree(proof.get("readback"));
   assertThat(readback).isEqualTo(data);
   assertThat(((Number)proof.get("recordVersion")).intValue()).isEqualTo(1);
   assertThat(service.contract(id)).containsEntry("status","DRAFT");
   System.out.println("AGREEMENT_SANDBOX_TYPES="+types);
   System.out.println("AGREEMENT_SANDBOX_PROOF="+proof);
  } finally {SSLContext.setDefault(previous);}
 }
}
