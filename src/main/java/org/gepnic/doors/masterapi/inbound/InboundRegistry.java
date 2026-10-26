package org.gepnic.doors.masterapi.inbound;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.gepnic.doors.masterapi.repository.*;
import java.util.*;
@Service
public class InboundRegistry {
    private final JdbcTemplate db;private final AgentRepository agents;private final ApiClientRepository clients;
    public InboundRegistry(JdbcTemplate db,AgentRepository agents,ApiClientRepository clients){this.db=db;this.agents=agents;this.clients=clients;}
    public record Route(long id,String service,String instance,String agent,long client,boolean enabled){}
    public record Registration(String service,String instanceId,String agentId,Long clientId,Long deploymentId){}
    public void validateVersions(Route route,Integer protocol,Integer contract){
        var versions=db.queryForMap("SELECT r.protocol_version,c.contract_version FROM inbound_write_routes r JOIN write_deployments d ON d.id=r.deployment_id JOIN write_contracts c ON c.id=d.contract_id WHERE r.id=?",route.id());
        if(protocol!=null && protocol!=((Number)versions.get("protocol_version")).intValue())throw new IllegalArgumentException("Unsupported API protocol version for this mapping");
        if(contract!=null && contract!=((Number)versions.get("contract_version")).intValue())throw new IllegalArgumentException("JSON contract version differs from the pinned mapping");
    }
    public List<Map<String,Object>> list(){return db.queryForList("SELECT r.*,c.contract_version,c.title FROM inbound_write_routes r LEFT JOIN write_deployments d ON d.id=r.deployment_id LEFT JOIN write_contracts c ON c.id=d.contract_id ORDER BY r.id");}
    public Route require(String service,String instance,long client){
        return db.query("SELECT r.* FROM inbound_write_routes r JOIN write_deployments d ON d.id=r.deployment_id JOIN write_contracts c ON c.id=d.contract_id WHERE r.service_code=? AND r.instance_id=? AND r.client_id=? AND r.enabled=true AND d.status='VERIFIED' AND c.status='APPROVED' AND (c.definition_hash=? OR c.definition_json->>'kind'='json-write-v1') AND r.instance_id=d.instance_id AND r.agent_id=d.agent_id AND r.service_code=c.service_code",
            (rs,n)->new Route(rs.getLong("id"),rs.getString("service_code"),rs.getString("instance_id"),rs.getString("agent_id"),rs.getLong("client_id"),true),service,instance,client,org.gepnic.doors.inbound.AgreementDefinition.hash())
            .stream().findFirst().orElseThrow(()->new SecurityException("Write service or destination is not authorised"));
    }
    @org.springframework.transaction.annotation.Transactional
    public void register(Registration r,String actor){
        if(r.service()==null || !r.service().matches("[a-z][a-z-]{0,39}") || r.instanceId()==null || !r.instanceId().matches("[A-Za-z0-9_-]{1,80}")
            || r.agentId()==null || r.clientId()==null)throw new IllegalArgumentException("Invalid write mapping");
        if(r.deploymentId()==null)throw new SecurityException("Select a verified approved Write Library destination");
        db.queryForList("SELECT c.id FROM write_contracts c JOIN write_deployments d ON d.contract_id=c.id WHERE d.id=? FOR UPDATE OF c",r.deploymentId());
        var destinations=db.queryForList("SELECT d.id FROM write_deployments d JOIN write_contracts c ON c.id=d.contract_id WHERE d.id=? AND d.instance_id=? AND d.agent_id=? AND c.service_code=? AND d.status='VERIFIED' AND c.status='APPROVED' AND (c.definition_hash=? OR c.definition_json->>'kind'='json-write-v1')",
            r.deploymentId(),r.instanceId(),r.agentId(),r.service(),org.gepnic.doors.inbound.AgreementDefinition.hash());
        if(destinations.size()!=1)throw new SecurityException("Approved verified destination required");
        agents.findById(r.agentId()).filter(a->Boolean.TRUE.equals(a.getIsActive())).orElseThrow(()->new IllegalArgumentException("Agent is inactive or absent"));
        clients.findById(r.clientId()).filter(c->Boolean.TRUE.equals(c.getIsActive())).orElseThrow(()->new IllegalArgumentException("Client is inactive or absent"));
        // Freeze destination for an instance; do not send retries to a different database.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","inbound-route:"+r.instanceId());
        Integer conflicts=db.queryForObject("SELECT count(*) FROM inbound_write_routes WHERE instance_id=? AND agent_id<>?",Integer.class,r.instanceId(),r.agentId());
        if(conflicts!=null && conflicts>0)throw new IllegalArgumentException("Instance is already bound to another Agent");
        int changed=db.update("INSERT INTO inbound_write_routes(service_code,instance_id,agent_id,client_id,created_by,deployment_id) VALUES(?,?,?,?,?,?) ON CONFLICT(service_code,instance_id,client_id) DO UPDATE SET deployment_id=EXCLUDED.deployment_id WHERE inbound_write_routes.deployment_id IS NULL AND inbound_write_routes.enabled=false AND inbound_write_routes.agent_id=EXCLUDED.agent_id",
            r.service(),r.instanceId(),r.agentId(),r.clientId(),actor,r.deploymentId());
        if(changed!=1)throw new IllegalStateException("Client mapping is already pinned to a contract; disable and review a migration before replacing it");
    }
    @org.springframework.transaction.annotation.Transactional
    public void enable(long id,boolean value){
        db.queryForList("SELECT c.id FROM write_contracts c JOIN write_deployments d ON d.contract_id=c.id JOIN inbound_write_routes r ON r.deployment_id=d.id WHERE r.id=? FOR UPDATE OF c",id);
        if(value && db.queryForObject("SELECT count(*) FROM inbound_write_routes r JOIN write_deployments d ON d.id=r.deployment_id JOIN write_contracts c ON c.id=d.contract_id WHERE r.id=? AND d.status='VERIFIED' AND c.status='APPROVED' AND (c.definition_hash=? OR c.definition_json->>'kind'='json-write-v1') AND r.instance_id=d.instance_id AND r.agent_id=d.agent_id AND r.service_code=c.service_code",Integer.class,id,org.gepnic.doors.inbound.AgreementDefinition.hash())!=1)
            throw new SecurityException("Approval and destination verification required before activation");
        if(db.update("UPDATE inbound_write_routes SET enabled=? WHERE id=?",value,id)!=1)throw new IllegalArgumentException("Unknown mapping");
    }
    public Long maxVersions(Route route){return db.queryForObject("SELECT max_versions FROM inbound_write_routes WHERE id=?",Long.class,route.id());}
    public void setMaxVersions(long id,Long value){
        if(value!=null&&(value<1||value>1000000))throw new IllegalArgumentException("Maximum versions must be 1–1000000, or null for unlimited");
        var rows=db.queryForList("SELECT c.definition_json FROM inbound_write_routes r JOIN write_deployments d ON d.id=r.deployment_id JOIN write_contracts c ON c.id=d.contract_id WHERE r.id=?",id);
        if(rows.size()!=1)throw new IllegalArgumentException("Unknown mapping");
        if(value!=null&&(!org.gepnic.doors.inbound.JsonWriteDefinition.dynamic(rows.getFirst().get("definition_json"))||!org.gepnic.doors.inbound.JsonWriteDefinition.automatic(org.gepnic.doors.inbound.JsonWriteDefinition.from(rows.getFirst().get("definition_json")))))throw new IllegalStateException("Approve and install the automatic-versioning contract before setting a limit");
        db.update("UPDATE inbound_write_routes SET max_versions=? WHERE id=?",value,id);
    }
    public boolean agentRouting(Route route){return Boolean.TRUE.equals(db.queryForObject("SELECT d.agent_routing FROM write_deployments d JOIN inbound_write_routes r ON r.deployment_id=d.id WHERE r.id=?",Boolean.class,route.id()));}
    public Object definition(Route route){
        var r=db.queryForMap("SELECT c.definition_json,c.definition_hash FROM inbound_write_routes r JOIN write_deployments d ON d.id=r.deployment_id JOIN write_contracts c ON c.id=d.contract_id WHERE r.id=?",route.id());
        if(org.gepnic.doors.inbound.JsonWriteDefinition.dynamic(r.get("definition_json"))){
            var spec=org.gepnic.doors.inbound.JsonWriteDefinition.from(r.get("definition_json"));
            if(!route.service().equals(spec.service())||!org.gepnic.doors.inbound.JsonWriteDefinition.hash(spec).equals(r.get("definition_hash")))throw new SecurityException("Write definition mismatch");return spec;
        }
        return null;
    }
    public Map<String,Object> options(){
        return Map.of("deployments",db.queryForList("SELECT d.*,c.title,c.contract_version,c.service_code FROM write_deployments d JOIN write_contracts c ON c.id=d.contract_id WHERE d.status='VERIFIED' AND c.status='APPROVED' AND (c.definition_hash=? OR c.definition_json->>'kind'='json-write-v1')",org.gepnic.doors.inbound.AgreementDefinition.hash()),"services",db.queryForList("SELECT DISTINCT service_code AS code FROM write_contracts WHERE status='APPROVED'"),
            "agents",agents.findAll().stream().filter(a->Boolean.TRUE.equals(a.getIsActive())).map(a->Map.of("id",a.getAgentId(),"name",a.getDisplayName(),"isSandbox",Boolean.TRUE.equals(a.getIsSandbox()))).toList(),
            "clients",clients.findAll().stream().filter(c->Boolean.TRUE.equals(c.getIsActive())).map(c->Map.of("id",c.getClientId(),"name",c.getClientName())).toList());
    }
}
