package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfEnvironmentVariable(named="DOORS_INBOUND_TEST_JDBC",matches=".+")
class WriteGovernancePostgresTest {
 JdbcTemplate db;WriteGovernanceService service;WriteAgentControl control;TransactionTemplate tx;InboundRegistry registry;
 String body="{\"tender_id\": \"1001\", \"agreement_no\": \"AGR/TEST\", \"agreement_date\": \"2026-10-12\"}";
 @BeforeEach void setup()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("DOORS_INBOUND_TEST_JDBC"),"doors_test","");
  db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  control=mock(WriteAgentControl.class);service=new WriteGovernanceService(db,control);
  when(control.call(eq("governance-test"),eq("sandbox"),eq("sandbox"),eq(body))).thenReturn(Map.of("success",true,"sampleHash",InboundProtocol.sha(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"rolledBack",true));
  when(control.call(eq("governance-test"),eq("verify"),anyString(),isNull())).thenAnswer(i->Map.of("instance",(String)i.getArgument(2),"success",true));
  var agents=mock(AgentRepository.class);var a=new Agent();a.setIsActive(true);when(agents.findById("governance-test")).thenReturn(Optional.of(a));
  var clients=mock(ApiClientRepository.class);var c=new ApiClient();c.setIsActive(true);when(clients.findById(1L)).thenReturn(Optional.of(c));
  registry=new InboundRegistry(db,agents,clients);
 }
 <T>T transaction(Supplier<T> s){return tx.execute(status->s.get());}
 long draft(){return transaction(()->service.create(new WriteGovernanceService.Proposal("Agreement test","Test governance",body,null),new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));}
 void test(long id){transaction(()->{try{return service.test(id,"governance-test","alice");}catch(Exception e){throw new RuntimeException(e);}});}
 void submit(long id){transaction(()->{service.submit(id,"alice");return null;});}
 void review(long id,String who,boolean accept){transaction(()->{service.review(id,who,accept,"Reviewed sample and SQL");return null;});}
 @Test void lifecycleEnforcesEvidenceIndependentApprovalVerificationAndExplicitGrant()throws Exception{
  long id=draft();
  assertThatThrownBy(()->submit(id)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->service.test(id,"governance-test","bob")).isInstanceOf(SecurityException.class);
  test(id);submit(id);
  assertThatThrownBy(()->review(id,"alice",true)).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->service.deploy(new WriteGovernanceService.Destination(id,"unapproved","governance-test"),"bob")).isInstanceOf(SecurityException.class);
  review(id,"bob",true);
  assertThatThrownBy(()->review(id,"bob",true)).isInstanceOf(IllegalStateException.class);
  String instance="govern_"+UUID.randomUUID().toString().replace("-","");
  long destination=transaction(()->service.deploy(new WriteGovernanceService.Destination(id,instance,"governance-test"),"bob"));
  var assignment=new InboundRegistry.Registration("agreements",instance,"governance-test",1L,destination);
  assertThatThrownBy(()->registry.register(assignment,"bob")).isInstanceOf(SecurityException.class);
  transaction(()->{try{return service.verify(destination,"bob");}catch(Exception e){throw new RuntimeException(e);}});
  transaction(()->{registry.register(assignment,"bob");return null;});
  long route=db.queryForObject("SELECT id FROM inbound_write_routes WHERE instance_id=?",Long.class,instance);
  assertThatThrownBy(()->registry.require("agreements",instance,1)).isInstanceOf(SecurityException.class);
  registry.enable(route,true);
  assertThat(registry.require("agreements",instance,1).agent()).isEqualTo("governance-test");
  registry.enable(route,false);
  assertThatThrownBy(()->registry.require("agreements",instance,1)).isInstanceOf(SecurityException.class);
  assertThat(service.events(id)).hasSize(6);
 }
 @Test void rejectionIsImmutableAndCannotEnterLibrary(){
  long id=draft();test(id);submit(id);review(id,"bob",false);
  assertThat(service.contract(id).get("status")).isEqualTo("REJECTED");
  assertThatThrownBy(()->submit(id)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->service.deploy(new WriteGovernanceService.Destination(id,"rejected","governance-test"),"bob")).isInstanceOf(SecurityException.class);
 }
 @Test void forgedSandboxEvidenceCannotBeSubmitted()throws Exception{
  long id=draft();when(control.call(eq("governance-test"),eq("sandbox"),eq("sandbox"),eq(body))).thenReturn(Map.of("sampleHash","wrong","rolledBack",true));
  assertThatThrownBy(()->service.test(id,"governance-test","alice")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->submit(id)).isInstanceOf(IllegalStateException.class);
 }

 @Test void libraryDisableDoesNotRestoreClientAccessOnReenable()throws Exception{
  long id=draft();test(id);submit(id);review(id,"bob",true);
  String instance="library_"+UUID.randomUUID().toString().replace("-","");
  long deployment=transaction(()->service.deploy(new WriteGovernanceService.Destination(id,instance,"governance-test"),"bob"));
  transaction(()->{try{return service.verify(deployment,"bob");}catch(Exception e){throw new RuntimeException(e);}});
  transaction(()->{registry.register(new InboundRegistry.Registration("agreements",instance,"governance-test",1L,deployment),"bob");return null;});
  long route=db.queryForObject("SELECT id FROM inbound_write_routes WHERE instance_id=?",Long.class,instance);
  transaction(()->{registry.enable(route,true);return null;});
  assertThat(registry.require("agreements",instance,1).enabled()).isTrue();
  transaction(()->{service.catalogue(id,new WriteGovernanceService.Catalogue("Contracts","Agreement"),"bob");service.setEnabled(id,false,"bob");return null;});
  assertThat(service.contract(id)).containsEntry("status","DISABLED").containsEntry("category","Contracts");
  assertThatThrownBy(()->registry.require("agreements",instance,1)).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->registry.enable(route,true)).isInstanceOf(SecurityException.class);
  transaction(()->{service.setEnabled(id,true,"bob");return null;});
  assertThat(service.contract(id).get("status")).isEqualTo("APPROVED");
  assertThatThrownBy(()->registry.require("agreements",instance,1)).isInstanceOf(SecurityException.class);
  transaction(()->{registry.enable(route,true);return null;});
  assertThat(registry.require("agreements",instance,1).enabled()).isTrue();
  assertThat(service.library().stream().filter(r->((Number)r.get("id")).longValue()==id).findFirst().orElseThrow().get("destinations").toString()).contains(instance);
  assertThat(service.events(id).stream().map(r->r.get("action"))).contains("CATALOGUE_UPDATED","SERVICE_DISABLED","SERVICE_REENABLED");
 }
 @Test void rejectedContractsRemainReviewedHistoryAndDraftsAreNotInLibrary(){
  long rejected=draft();test(rejected);submit(rejected);review(rejected,"bob",false);
  long draft=draft();
  assertThat(service.library().stream().map(r->((Number)r.get("id")).longValue())).contains(rejected).doesNotContain(draft);
  assertThatThrownBy(()->service.setEnabled(rejected,true,"bob")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->service.catalogue(rejected,new WriteGovernanceService.Catalogue("Changed",""),"bob")).isInstanceOf(IllegalStateException.class);
 }

 @Test void discoveredJsonContractFlowsThroughApprovalAndRegistry()throws Exception{
  String code="evaluation-"+UUID.randomUUID().toString().substring(0,8).replaceAll("[0-9]","a");
  String sample="{\"evaluation_id\":\"EV1\",\"tender_id\":\"1001\",\"score\":90}";
  var proposal=new WriteGovernanceService.Proposal("Evaluation records","Review generic fields",sample,null,code,List.of("/tender_id","/evaluation_id"),Map.of("/score","text"));
  long id=transaction(()->service.create(proposal,new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));
  when(control.callDynamic(eq("governance-test"),eq("sandbox"),eq("sandbox"),eq(sample),any())).thenReturn(Map.of("success",true,"sampleHash",InboundProtocol.sha(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"rolledBack",true));
  when(control.callDynamic(eq("governance-test"),eq("verify"),anyString(),isNull(),any())).thenAnswer(i->Map.of("instance",(String)i.getArgument(2),"success",true));
  test(id);submit(id);review(id,"bob",true);
  String instance="json_"+UUID.randomUUID().toString().replace("-","");
  long destination=transaction(()->service.deploy(new WriteGovernanceService.Destination(id,instance,"governance-test"),"bob"));
  transaction(()->{try{return service.verify(destination,"bob");}catch(Exception e){throw new RuntimeException(e);}});
  transaction(()->{registry.register(new InboundRegistry.Registration(code,instance,"governance-test",1L,destination),"bob");return null;});
  long route=db.queryForObject("SELECT id FROM inbound_write_routes WHERE instance_id=?",Long.class,instance);registry.enable(route,true);
  var definition=(JsonWriteDefinition.Spec)registry.definition(registry.require(code,instance,1));
  assertThat(definition.businessKey()).containsExactly("/tender_id","/evaluation_id");
  assertThat(definition.fields().stream().filter(f->f.path().equals("/score")).findFirst().orElseThrow().type()).isEqualTo("text");
  service.setEnabled(id,false,"bob");assertThatThrownBy(()->registry.require(code,instance,1)).isInstanceOf(SecurityException.class);
 }

 @Test void postgresTypesValidateDatesAndJsonBeforeProposal(){
  String sample="{\"record_id\":\"R1\",\"signed_on\":\"2026-10-17\",\"details\":{\"notes\":\"ok\"},\"counts\":[1,2]}";
  var spec=JsonWriteDefinition.create("date-test",List.of("/record_id"),sample,Map.of("/signed_on","date","/details","jsonb","/counts","integer[]"));
  service.validateTypes(spec,sample);
  assertThatThrownBy(()->JsonWriteDefinition.validate(spec,sample.replace("2026-10-17","2026-02-30"))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.validateTypes(spec,sample.replace("[1,2]","[1,999999999999999999999]"))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void reviewerEditsInvalidateEvidenceAndRequireCurrentFingerprint()throws Exception{
  var proposal=new WriteGovernanceService.Proposal("Review edit test","Test revised columns",body,null,"review-edit",List.of("/tender_id","/agreement_no"));
  long id=transaction(()->service.create(proposal,new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));
  when(control.callDynamic(eq("governance-test"),eq("sandbox"),eq("sandbox"),eq(body),any())).thenReturn(Map.of("sampleHash",InboundProtocol.sha(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"rolledBack",true));
  test(id);submit(id);String old=service.contract(id).get("definition_hash").toString();
  var edit=new WriteGovernanceService.Amendment(old,body,List.of("/tender_id","/agreement_no"),Map.of("/agreement_date","date"),Map.of("/agreement_no","contract_number"),"Use readable columns and a date");
  assertThatThrownBy(()->transaction(()->{service.amend(id,edit,"alice");return null;})).isInstanceOf(SecurityException.class);
  transaction(()->{service.amend(id,edit,"bob");return null;});
  assertThat(service.contract(id)).containsEntry("status","PENDING").containsEntry("tested_at",null);
  assertThatThrownBy(()->review(id,"bob",true)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->transaction(()->{service.amend(id,edit,"bob");return null;})).isInstanceOf(IllegalStateException.class);
  transaction(()->{try{return service.reviewSandbox(id,"governance-test","bob");}catch(Exception e){throw new RuntimeException(e);}});
  assertThatThrownBy(()->transaction(()->{service.reviewCurrent(id,"bob",true,"Approved",old);return null;})).isInstanceOf(IllegalStateException.class);
  String current=service.contract(id).get("definition_hash").toString();
  transaction(()->{service.reviewCurrent(id,"bob",true,"Approved revised SQL",current);return null;});
  assertThat(service.contract(id).get("status")).isEqualTo("APPROVED");
  assertThat(service.events(id).stream().map(r->r.get("action"))).contains("REVIEW_DEFINITION_CHANGED");
  assertThat(JsonWriteDefinition.ddl(JsonWriteDefinition.from(service.contract(id).get("definition_json")))).contains("contract_number").contains("doors_write.review_edit");
 }


 @Test void draftOwnerCanRemoveColumnsAndMustRetest()throws Exception{
  long id=transaction(()->service.create(new WriteGovernanceService.Proposal("Editable draft","Remove optional extraction",body,null,"editable-draft",List.of("/tender_id","/agreement_no")),new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("DATAMANAGER")))));
  when(control.callDynamic(eq("governance-test"),eq("sandbox"),eq("sandbox"),eq(body),any())).thenReturn(Map.of("sampleHash",InboundProtocol.sha(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"rolledBack",true));
  test(id);
  var edit=new WriteGovernanceService.Amendment(service.contract(id).get("definition_hash").toString(),body,List.of("/tender_id","/agreement_no"),null,null,"Retain keys only",List.of("/tender_id","/agreement_no"));
  assertThatThrownBy(()->transaction(()->{service.amendDraft(id,edit,"bob");return null;})).isInstanceOf(SecurityException.class);
  transaction(()->{service.amendDraft(id,edit,"alice");return null;});
  assertThat(service.contract(id)).containsEntry("status","DRAFT").containsEntry("tested_at",null).containsEntry("sample_json",body);
  assertThat(JsonWriteDefinition.from(service.contract(id).get("definition_json")).fields()).hasSize(2);
  assertThatThrownBy(()->submit(id)).isInstanceOf(IllegalStateException.class);
  test(id);submit(id);review(id,"bob",true);
  assertThatThrownBy(()->transaction(()->{service.amendDraft(id,edit,"alice");return null;})).isInstanceOf(IllegalStateException.class);
 }
}

