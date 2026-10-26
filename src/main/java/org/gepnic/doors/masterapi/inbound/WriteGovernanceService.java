package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.service.GovernanceReviewRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import java.util.*;
@Service
public class WriteGovernanceService {
 private final JdbcTemplate db;private final WriteAgentControl agents;
 public WriteGovernanceService(JdbcTemplate db,WriteAgentControl a){this.db=db;agents=a;}
 static void validateServiceDetails(String title,String purpose){
  if(title==null||title.isBlank())throw new IllegalArgumentException("Service Title is required.");
  if(title.strip().length()<3||title.length()>100)throw new IllegalArgumentException("Service Title must contain 3–100 characters (excluding leading/trailing spaces for the minimum).");
  if(title.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.LINE_SEPARATOR||Character.getType(c)==Character.PARAGRAPH_SEPARATOR))throw new IllegalArgumentException("Service Title must be a single line without control or invisible formatting characters. Normal punctuation is allowed.");
  if(purpose==null||purpose.isBlank())throw new IllegalArgumentException("Purpose is required.");
  if(purpose.length()>4000)throw new IllegalArgumentException("Purpose must not exceed 4,000 characters.");
 }
 public record Proposal(String title,String purpose,String sample,Long requestId,String service,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,List<String> selectedFields,String additionalFieldPolicy){
  public Proposal(String title,String purpose,String sample,Long requestId,String service,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,List<String> selectedFields){this(title,purpose,sample,requestId,service,businessKey,fieldTypes,fieldColumns,selectedFields,null);}
  public Proposal(String title,String purpose,String sample,Long requestId,String service,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns){this(title,purpose,sample,requestId,service,businessKey,fieldTypes,fieldColumns,null,null);}
  public Proposal(String title,String purpose,String sample,Long requestId,String service,List<String> businessKey,Map<String,String> fieldTypes){this(title,purpose,sample,requestId,service,businessKey,fieldTypes,null,null,null);}
  public Proposal(String title,String purpose,String sample,Long requestId,String service,List<String> businessKey){this(title,purpose,sample,requestId,service,businessKey,null,null,null,null);}
  public Proposal(String title,String purpose,String sample,Long requestId){this(title,purpose,sample,requestId,null,null,null,null,null,null);}
 }
 public record Destination(long contractId,String instanceId,String agentId){}
 private String json(Object o){try{return InboundProtocol.JSON.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Invalid evidence");}}
 public void validateTypes(JsonWriteDefinition.Spec spec,String sample){
  if(!JsonWriteDefinition.extended(spec))return;
  var values=JsonWriteDefinition.mappedSample(spec,sample);
  for(var f:spec.fields()){
   try{db.queryForObject("SELECT to_jsonb(r)::text FROM jsonb_to_record(?::jsonb) AS r(\""+f.column()+"\" "+f.type()+")",String.class,json(Collections.singletonMap(f.column(),values.get(f.column()))));}
   catch(org.springframework.dao.DataAccessException e){throw new IllegalArgumentException("Sample field "+f.path()+" cannot be stored as "+f.type()+". Dates must use ISO format; arrays must be JSON arrays.");}
  }
 }
 private void event(long id,String actor,String action,String detail){db.update("INSERT INTO write_governance_events(contract_id,actor,action,detail) VALUES(?,?,?,?)",id,actor,action,detail);}
 public Map<String,Object> contract(long id){var rows=db.queryForList("SELECT * FROM write_contracts WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("Unknown write contract");return rows.getFirst();}
 private Map<String,Object> locked(long id){var rows=db.queryForList("SELECT * FROM write_contracts WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new IllegalArgumentException("Unknown write contract");return rows.getFirst();}
 private void owner(Map<String,Object> r,String actor){if(!actor.equals(r.get("proposer")))throw new SecurityException("Only the proposer may test or submit");if(!"DRAFT".equals(r.get("status")))throw new IllegalStateException("Create a new proposal for changes");}
 private void current(Map<String,Object> r){if(!(JsonWriteDefinition.dynamic(r.get("definition_json"))?JsonWriteDefinition.hash(JsonWriteDefinition.from(r.get("definition_json"))):AgreementDefinition.hash()).equals(r.get("definition_hash")))throw new IllegalStateException("Definition changed: generate and test a new proposal");}
 private void approved(Map<String,Object> r){current(r);if(!"APPROVED".equals(r.get("status")))throw new SecurityException("Approved write contract required");}
 @Transactional public long create(Proposal p,Authentication auth){
  validateServiceDetails(p.title(),p.purpose());
  if(p.sample()==null||p.sample().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>InboundProtocol.MAX_BODY)throw new IllegalArgumentException("Sample exceeds 1 MiB");
  boolean dynamic=p.service()!=null;
  JsonWriteDefinition.Spec spec=dynamic?JsonWriteDefinition.createSelected(p.service(),p.businessKey(),p.sample(),p.fieldTypes(),p.fieldColumns(),p.selectedFields(),p.additionalFieldPolicy()==null?JsonWriteDefinition.REJECT_UNAPPROVED:p.additionalFieldPolicy()):null;
  if(!dynamic)AgreementContract.validate(p.sample());else validateTypes(spec,p.sample());
  String code=dynamic?spec.service():"agreements",hash=dynamic?JsonWriteDefinition.hash(spec):AgreementDefinition.hash();
  Object definition=dynamic?JsonWriteDefinition.describe(spec):AgreementDefinition.describe();
  if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equalsIgnoreCase("Developer")||a.getAuthority().equalsIgnoreCase("ROLE_DEVELOPER"))){
   Integer n=db.queryForObject("SELECT count(*) FROM data_pull_requests WHERE request_id=? AND upper(status)='APPROVED'",Integer.class,p.requestId());
   if(n==null||n!=1)throw new SecurityException("An approved data request is required for Developer submissions");
  }
  db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","write-contract:"+code);
  Integer version=db.queryForObject("SELECT coalesce(max(contract_version),0)+1 FROM write_contracts WHERE service_code=?",Integer.class,code);
  Long id=db.queryForObject("INSERT INTO write_contracts(service_code,contract_version,title,purpose,sample_json,definition_hash,definition_json,status,proposer,request_id) VALUES(?,?,?,?,?,?,?::jsonb,'DRAFT',?,?) RETURNING id",
   Long.class,code,version,p.title(),p.purpose(),p.sample(),hash,json(definition),auth.getName(),p.requestId());
  if(dynamic)db.update("UPDATE write_contracts SET subcategory=? WHERE id=?",code,id);
  event(id,auth.getName(),"GENERATED",code+"; immutable reviewed definition");return id;
 }
 @Transactional public Map<String,Object> test(long id,String agent,String actor) throws Exception {
  return sandbox(locked(id),id,agent,actor,false);
 }
 private Map<String,Object> sandbox(Map<String,Object> row,long id,String agent,String actor,boolean reviewer)throws Exception{
  if(reviewer)GovernanceReviewRules.requirePendingReviewer(row.get("status").toString(),row.get("proposer").toString(),actor);else owner(row,actor);current(row);
  var proof=JsonWriteDefinition.dynamic(row.get("definition_json"))?agents.callDynamic(agent,"sandbox","sandbox",row.get("sample_json").toString(),JsonWriteDefinition.from(row.get("definition_json"))):agents.call(agent,"sandbox","sandbox",row.get("sample_json").toString());
  if(!InboundProtocol.sha(row.get("sample_json").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(proof.get("sampleHash"))
    || !Boolean.TRUE.equals(proof.get("rolledBack")))throw new IllegalStateException("Sandbox evidence does not match sample");
  db.update("UPDATE write_contracts SET sandbox_agent=?,sandbox_evidence=?::jsonb,tested_at=CURRENT_TIMESTAMP WHERE id=?",agent,json(proof),id);
  event(id,actor,"SANDBOX_PASSED","Created table, wrote sample, read back, rolled back");return proof;
 }
 public record Amendment(String expectedHash,String sample,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,String note,List<String> selectedFields,String additionalFieldPolicy){public Amendment(String expectedHash,String sample,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,String note){this(expectedHash,sample,businessKey,fieldTypes,fieldColumns,note,null,null);}public Amendment(String expectedHash,String sample,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,String note,List<String> selectedFields){this(expectedHash,sample,businessKey,fieldTypes,fieldColumns,note,selectedFields,null);}}
 @Transactional public void amendDraft(long id,Amendment a,String actor){var row=locked(id);owner(row,actor);revise(row,id,a,actor);}
 @Transactional public void amend(long id,Amendment a,String actor){var row=locked(id);GovernanceReviewRules.requirePendingReviewer(row.get("status").toString(),row.get("proposer").toString(),actor);revise(row,id,a,actor);}
 private void revise(Map<String,Object> row,long id,Amendment a,String actor){
  current(row);
  if(a.expectedHash()==null||!a.expectedHash().equals(row.get("definition_hash")))throw new IllegalStateException("Definition changed; refresh before editing");
  if(a.note()==null||a.note().isBlank()||a.note().length()>4000)throw new IllegalArgumentException("Explain the review changes");
  if(!JsonWriteDefinition.dynamic(row.get("definition_json")))throw new IllegalStateException("Create a JSON proposal to revise a legacy agreement");
  var existing=JsonWriteDefinition.from(row.get("definition_json"));
  var policy=a.additionalFieldPolicy()==null?JsonWriteDefinition.additionalFieldPolicy(existing):a.additionalFieldPolicy();
  var spec=JsonWriteDefinition.createSelected(row.get("service_code").toString(),a.businessKey(),a.sample(),a.fieldTypes(),a.fieldColumns(),a.selectedFields(),policy);
  validateTypes(spec,a.sample());var revised=JsonWriteDefinition.describe(spec);
  event(id,actor,"DRAFT".equals(row.get("status"))?"DRAFT_DEFINITION_CHANGED":"REVIEW_DEFINITION_CHANGED",json(Map.of("note",a.note(),"previousDefinition",row.get("definition_json").toString(),"previousSample",row.get("sample_json"),"newDefinition",revised,"newSample",a.sample())));
  db.update("UPDATE write_contracts SET sample_json=?,definition_json=?::jsonb,definition_hash=?,sandbox_agent=NULL,sandbox_evidence=NULL,tested_at=NULL WHERE id=?",a.sample(),json(revised),JsonWriteDefinition.hash(spec),id);
 }
 @Transactional public Map<String,Object> reviewSandbox(long id,String agent,String actor)throws Exception{return sandbox(locked(id),id,agent,actor,true);}
 @Transactional public void reviewCurrent(long id,String actor,boolean accept,String note,String expectedHash){
  var row=locked(id);if(expectedHash==null||!expectedHash.equals(row.get("definition_hash")))throw new IllegalStateException("Definition changed; refresh and review the latest SQL");
  review(id,actor,accept,note);
 }
 @Transactional public void submit(long id,String actor){
  var row=locked(id);owner(row,actor);current(row);
  if(row.get("tested_at")==null||row.get("sandbox_evidence")==null)throw new IllegalStateException("Successful sandbox test required");
  db.update("UPDATE write_contracts SET status='PENDING' WHERE id=?",id);event(id,actor,"SUBMITTED","Ready for independent review");
 }
 @Transactional public void review(long id,String actor,boolean accept,String note){
  var row=locked(id);GovernanceReviewRules.requirePendingReviewer(row.get("status").toString(),row.get("proposer").toString(),actor);current(row);
  if(accept&&row.get("tested_at")==null)throw new IllegalStateException("Sandbox evidence required after review changes");
  if(note==null||note.isBlank()||note.length()>4000)throw new IllegalArgumentException("Review remarks required");
  db.update("UPDATE write_contracts SET status=?,reviewer=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",accept?"APPROVED":"REJECTED",actor,note,id);
  event(id,actor,accept?"APPROVED":"REJECTED",note);
 }
 public List<Map<String,Object>> list(String status,String owner){
  if(owner!=null)return downloadable(db.queryForList("SELECT * FROM write_contracts WHERE proposer=? ORDER BY id DESC",owner));
  return downloadable(db.queryForList("SELECT * FROM write_contracts WHERE status=? ORDER BY id DESC",status));
 }
 public record AgentMapping(long contractId,List<String> agentIds){}
 @Transactional public List<Long> mapAgents(AgentMapping request,String actor){
  var contract=locked(request.contractId());approved(contract);
  if(!JsonWriteDefinition.dynamic(contract.get("definition_json")))throw new IllegalArgumentException("Agent-only mapping requires a JSON write contract");
  if(request.agentIds()==null||request.agentIds().isEmpty()||request.agentIds().size()>100||new HashSet<>(request.agentIds()).size()!=request.agentIds().size())throw new IllegalArgumentException("Select 1-100 different Agents");
  var ids=new ArrayList<Long>();
  for(String agent:request.agentIds()){
   if(agent==null||db.queryForObject("SELECT count(*) FROM agents WHERE agent_id=? AND is_active=true",Integer.class,agent)!=1)throw new IllegalArgumentException("Active Agent required");
   var existing=db.queryForList("SELECT id FROM write_deployments WHERE contract_id=? AND agent_id=? ORDER BY id",Long.class,request.contractId(),agent);
   if(!existing.isEmpty()){ids.addAll(existing);continue;}
   var previous=db.queryForList("SELECT DISTINCT d.instance_id FROM write_deployments d JOIN write_contracts c ON c.id=d.contract_id WHERE d.agent_id=? AND c.service_code=?",String.class,agent,contract.get("service_code"));
   if(previous.size()>1)throw new IllegalStateException("Multiple historical destinations for Agent "+agent+"; review existing mappings before adding a new contract");
   String instance=previous.isEmpty()?"agent_"+InboundProtocol.sha(agent.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,24):previous.getFirst();
   long id=deploy(new Destination(request.contractId(),instance,agent),actor);
   // Existing namespaces keep their original Agent-local routing to preserve retry destinations.
   if(previous.isEmpty()||db.queryForObject("SELECT count(*) FROM write_deployments WHERE agent_id=? AND instance_id=? AND agent_routing=true",Integer.class,agent,instance)>0)db.update("UPDATE write_deployments SET agent_routing=true WHERE id=?",id);
   ids.add(id);
  }
  return ids;
 }
 @Transactional public long deploy(Destination d,String actor){
  approved(locked(d.contractId()));
  if(d.instanceId()==null||!d.instanceId().matches("[A-Za-z0-9_-]{1,80}")||d.agentId()==null)throw new IllegalArgumentException("Select an instance and Agent");
  db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","inbound-route:"+d.instanceId());
  Integer conflicts=db.queryForObject("SELECT count(*) FROM write_deployments WHERE instance_id=? AND agent_id<>?",Integer.class,d.instanceId(),d.agentId());
  Integer oldConflicts=db.queryForObject("SELECT count(*) FROM inbound_write_routes WHERE instance_id=? AND agent_id<>?",Integer.class,d.instanceId(),d.agentId());
  if(conflicts>0||oldConflicts>0)throw new IllegalArgumentException("Instance already bound to another Agent");
  if(db.queryForObject("SELECT count(*) FROM agents WHERE agent_id=? AND is_active=true",Integer.class,d.agentId())!=1)throw new IllegalArgumentException("Active Agent required");
  Long id=db.queryForObject("INSERT INTO write_deployments(contract_id,instance_id,agent_id,created_by) VALUES(?,?,?,?) RETURNING id",Long.class,d.contractId(),d.instanceId(),d.agentId(),actor);
  event(d.contractId(),actor,"DESTINATION_REGISTERED",d.instanceId());return id;
 }
 @Transactional public Map<String,Object> verify(long id,String actor) throws Exception {
  var rows=db.queryForList("SELECT * FROM write_deployments WHERE id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new IllegalArgumentException("Unknown destination");var d=rows.getFirst();
  var contract=contract(((Number)d.get("contract_id")).longValue());approved(contract);
  var proof=Boolean.TRUE.equals(d.get("agent_routing"))?agents.callAgentMapped(d.get("agent_id").toString(),"verify",d.get("instance_id").toString(),JsonWriteDefinition.from(contract.get("definition_json"))):JsonWriteDefinition.dynamic(contract.get("definition_json"))?agents.callDynamic(d.get("agent_id").toString(),"verify",d.get("instance_id").toString(),null,JsonWriteDefinition.from(contract.get("definition_json"))):agents.call(d.get("agent_id").toString(),"verify",d.get("instance_id").toString(),null);
  if(!d.get("instance_id").equals(proof.get("instance")))throw new IllegalStateException("Destination mismatch");
  db.update("UPDATE write_deployments SET status='VERIFIED',verification=?::jsonb,verified_at=CURRENT_TIMESTAMP WHERE id=?",json(proof),id);
  event(((Number)d.get("contract_id")).longValue(),actor,"DESTINATION_VERIFIED",d.get("instance_id").toString());return proof;
 }
 public List<Map<String,Object>> deployments(){return db.queryForList("SELECT d.*,c.title,c.contract_version,c.service_code FROM write_deployments d JOIN write_contracts c ON c.id=d.contract_id WHERE c.status='APPROVED' ORDER BY d.id DESC");}
 public List<Map<String,Object>> events(long id){return db.queryForList("SELECT actor,action,detail,occurred_at FROM write_governance_events WHERE contract_id=? ORDER BY id",id);}

 private List<Map<String,Object>> downloadable(List<Map<String,Object>> rows){
  for(var row:rows)if(JsonWriteDefinition.dynamic(row.get("definition_json"))){
   var spec=JsonWriteDefinition.from(row.get("definition_json"));
   row.put("definition_json",json(JsonWriteDefinition.describe(spec)));
  }
  return rows;
 }
 public List<Map<String,Object>> library(){
  return downloadable(db.queryForList("SELECT c.*,coalesce((SELECT jsonb_agg(jsonb_build_object('id',d.id,'agent_id',d.agent_id,'instance_id',d.instance_id,'status',d.status,'verified_at',d.verified_at) ORDER BY d.id) FROM write_deployments d WHERE d.contract_id=c.id),'[]'::jsonb) AS destinations FROM write_contracts c WHERE c.status IN ('APPROVED','DISABLED','REJECTED') ORDER BY c.id DESC"));
 }
 public record Catalogue(String category,String subcategory){}
 @Transactional public void catalogue(long id,Catalogue metadata,String actor){
  var row=locked(id);
  if(!Set.of("APPROVED","DISABLED").contains(row.get("status")))throw new IllegalStateException("Only reviewed services can be classified");
  if(metadata.category()==null||metadata.category().isBlank()||metadata.category().length()>80||metadata.subcategory()==null||metadata.subcategory().length()>80
    ||metadata.category().chars().anyMatch(c->c<32)||metadata.subcategory().chars().anyMatch(c->c<32))throw new IllegalArgumentException("Provide a category and an optional sub-category, up to 80 characters each");
  db.update("UPDATE write_contracts SET category=?,subcategory=? WHERE id=?",metadata.category().strip(),metadata.subcategory().strip(),id);
  event(id,actor,"CATALOGUE_UPDATED",json(metadata));
 }
 @Transactional public void setEnabled(long id,boolean enabled,String actor){
  var row=locked(id);
  String expected=enabled?"DISABLED":"APPROVED";
  if(!expected.equals(row.get("status")))throw new IllegalStateException("Service is no longer "+expected+". Refresh the library.");
  if(enabled)current(row);
  db.update("UPDATE write_contracts SET status=? WHERE id=?",enabled?"APPROVED":"DISABLED",id);
  // Reactivation never resurrects a previously enabled client assignment.
  db.update("UPDATE inbound_write_routes SET enabled=false WHERE deployment_id IN (SELECT id FROM write_deployments WHERE contract_id=?)",id);
  event(id,actor,enabled?"SERVICE_REENABLED":"SERVICE_DISABLED","Client assignments remain disabled; stored history is retained");
 }
}
