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
    public record Registration(String service,String instanceId,String agentId,Long clientId){}
    public List<Map<String,Object>> list(){return db.queryForList("SELECT id,service_code,instance_id,agent_id,client_id,enabled,created_by,created_at FROM inbound_write_routes ORDER BY id");}
    public Route require(String service,String instance,long client){
        return db.query("SELECT * FROM inbound_write_routes WHERE service_code=? AND instance_id=? AND client_id=? AND enabled=true",
            (rs,n)->new Route(rs.getLong("id"),rs.getString("service_code"),rs.getString("instance_id"),rs.getString("agent_id"),rs.getLong("client_id"),true),service,instance,client)
            .stream().findFirst().orElseThrow(()->new SecurityException("Write service or destination is not authorised"));
    }
    @org.springframework.transaction.annotation.Transactional
    public void register(Registration r,String actor){
        if(!"agreements".equals(r.service()) || r.instanceId()==null || !r.instanceId().matches("[A-Za-z0-9_-]{1,80}")
            || r.agentId()==null || r.clientId()==null)throw new IllegalArgumentException("Invalid write mapping");
        agents.findById(r.agentId()).filter(a->Boolean.TRUE.equals(a.getIsActive())).orElseThrow(()->new IllegalArgumentException("Agent is inactive or absent"));
        clients.findById(r.clientId()).filter(c->Boolean.TRUE.equals(c.getIsActive())).orElseThrow(()->new IllegalArgumentException("Client is inactive or absent"));
        // Freeze destination for an instance; do not send retries to a different database.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","inbound-route:"+r.instanceId());
        Integer conflicts=db.queryForObject("SELECT count(*) FROM inbound_write_routes WHERE instance_id=? AND agent_id<>?",Integer.class,r.instanceId(),r.agentId());
        if(conflicts!=null && conflicts>0)throw new IllegalArgumentException("Instance is already bound to another Agent");
        db.update("INSERT INTO inbound_write_routes(service_code,instance_id,agent_id,client_id,created_by) VALUES(?,?,?,?,?)",
            r.service(),r.instanceId(),r.agentId(),r.clientId(),actor);
    }
    public void enable(long id,boolean value){
        if(db.update("UPDATE inbound_write_routes SET enabled=? WHERE id=?",value,id)!=1)throw new IllegalArgumentException("Unknown mapping");
    }
    public Map<String,Object> options(){
        return Map.of("services",List.of(Map.of("code","agreements","schemaVersion",1,"key","tender_id + agreement_no")),
            "agents",agents.findAll().stream().filter(a->Boolean.TRUE.equals(a.getIsActive())).map(a->Map.of("id",a.getAgentId(),"name",a.getDisplayName())).toList(),
            "clients",clients.findAll().stream().filter(c->Boolean.TRUE.equals(c.getIsActive())).map(c->Map.of("id",c.getClientId(),"name",c.getClientName())).toList());
    }
}
