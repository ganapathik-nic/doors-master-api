package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;
@RestController @RequestMapping("/api/v1/master/governance/writes")
public class WriteGovernanceController {
 private final WriteGovernanceService service;private final InboundRegistry registry;
 public WriteGovernanceController(WriteGovernanceService s,InboundRegistry r){service=s;registry=r;}
 @GetMapping("/definition") public Object definition(){return AgreementDefinition.describe();}
 public record Analyse(String sample,String service,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns,List<String> selectedFields,String additionalFieldPolicy){public Analyse(String sample,String service,List<String> businessKey,Map<String,String> fieldTypes,Map<String,String> fieldColumns){this(sample,service,businessKey,fieldTypes,fieldColumns,null,null);}public Analyse(String sample,String service,List<String> businessKey,Map<String,String> fieldTypes){this(sample,service,businessKey,fieldTypes,null,null,null);}}
 @PostMapping("/analyse") public Object analyse(@RequestBody Analyse a){
  var fields=JsonWriteDefinition.extractableFields(a.sample());
  if(a.businessKey()!=null&&!a.businessKey().isEmpty()){var spec=JsonWriteDefinition.createSelected(a.service(),a.businessKey(),a.sample(),a.fieldTypes(),a.fieldColumns(),a.selectedFields(),a.additionalFieldPolicy()==null?JsonWriteDefinition.REJECT_UNAPPROVED:a.additionalFieldPolicy());service.validateTypes(spec,a.sample());return JsonWriteDefinition.describe(spec);}
  String suggestion=fields.stream().filter(f->f.path().matches("/[a-z]+_(no|id|code)")).map(f->f.path().substring(1).replaceFirst("_(no|id|code)$","")).filter(n->!Set.of("tender","vendor","user").contains(n)).findFirst().orElse("document");
  return Map.of("suggestedService",suggestion+"-data","fields",fields,"suggestedKeys",JsonWriteDefinition.suggestedKeys(fields),"message","Review the suggested fields and types. The single payload column retains the complete JSON. Only scalar leaves are suggested as optional extracted columns. Null sample values default to optional text.");
 }
 @GetMapping("/options") public Object options(){
  var result=new LinkedHashMap<String,Object>(registry.options());
  var agents=(List<?>)result.get("agents");result.put("sandboxAgents",agents.stream().filter(a->a instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("isSandbox"))).toList());
  result.put("dataTypes",JsonWriteDefinition.types().stream().filter(t->!Set.of("json","jsonb","json[]","jsonb[]").contains(t)).toList());return result;
 }
 @GetMapping("/mine") public Object mine(Authentication a){return service.list(null,a.getName());}
 @PostMapping("/propose") public Object propose(@RequestBody WriteGovernanceService.Proposal p,Authentication a){return Map.of("id",service.create(p,a));}
 public record Test(String agentId){}
 @PostMapping("/{id}/sandbox") public Object sandbox(@PathVariable long id,@RequestBody Test t,Authentication a)throws Exception{return service.test(id,t.agentId(),a.getName());}
 @PutMapping("/{id}/draft-definition") public Object amendDraft(@PathVariable long id,@RequestBody WriteGovernanceService.Amendment change,Authentication a){service.amendDraft(id,change,a.getName());return Map.of("success",true);}
 @PostMapping("/{id}/submit") public Object submit(@PathVariable long id,Authentication a){service.submit(id,a.getName());return Map.of("status","PENDING");}
 @GetMapping("/pending") public Object pending(){return service.list("PENDING",null);}
 @GetMapping("/library") public Object library(){return service.list("APPROVED",null);}
 @PutMapping("/{id}/review-definition") public Object amend(@PathVariable long id,@RequestBody WriteGovernanceService.Amendment change,Authentication a){service.amend(id,change,a.getName());return Map.of("success",true,"status","PENDING","requiresSandbox",true);}
 @PostMapping("/{id}/review-sandbox") public Object reviewSandbox(@PathVariable long id,@RequestBody Test t,Authentication a)throws Exception{return service.reviewSandbox(id,t.agentId(),a.getName());}
 public record Review(boolean approve,String note,String expectedHash){}
 @PostMapping("/{id}/review") public Object review(@PathVariable long id,@RequestBody Review r,Authentication a){service.reviewCurrent(id,a.getName(),r.approve(),r.note(),r.expectedHash());return Map.of("success",true);}
 @GetMapping("/{id}/events") public Object events(@PathVariable long id){return service.events(id);}
 @GetMapping("/deployments") public Object deployments(){return service.deployments();}
 @PostMapping("/agent-mappings") public Object mapAgents(@RequestBody WriteGovernanceService.AgentMapping m,Authentication a){return Map.of("ids",service.mapAgents(m,a.getName()));}
 @PostMapping("/deployments") public Object deploy(@RequestBody WriteGovernanceService.Destination d,Authentication a){return Map.of("id",service.deploy(d,a.getName()));}
 @PostMapping("/deployments/{id}/verify") public Object verify(@PathVariable long id,Authentication a)throws Exception{return service.verify(id,a.getName());}
 @GetMapping("/library-items") public Object libraryItems(){return service.library();}
 @PutMapping("/{id}/catalogue") public Object catalogue(@PathVariable long id,@RequestBody WriteGovernanceService.Catalogue c,Authentication a){service.catalogue(id,c,a.getName());return Map.of("success",true);}
 public record Availability(boolean enabled){}
 @PutMapping("/{id}/availability") public Object availability(@PathVariable long id,@RequestBody Availability state,Authentication a){service.setEnabled(id,state.enabled(),a.getName());return Map.of("success",true);}
 @ExceptionHandler(IllegalStateException.class)
 public org.springframework.http.ResponseEntity<?> conflict(IllegalStateException e){
  return org.springframework.http.ResponseEntity.status(409).body(Map.of("code","WRITE_WORKFLOW_STATE","message",e.getMessage()));
 }
}
