package org.gepnic.doors.masterapi.inbound;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.Principal;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/inbound-services")
public class InboundAdminController {
    private final InboundRegistry registry;private final InboundProperties properties;private final JdbcTemplate db;
    public InboundAdminController(InboundRegistry r,InboundProperties p,JdbcTemplate db){registry=r;properties=p;this.db=db;}
    @GetMapping public Map<String,Object> list(){return Map.of("enabled",properties.isEnabled(),"routes",registry.list());}
    @GetMapping("/options") public Map<String,Object> options(){return registry.options();}
    @PostMapping public Map<String,String> create(@RequestBody InboundRegistry.Registration body,Principal actor){registry.register(body,actor.getName());return Map.of("message","Disabled write mapping registered");}
    public record Activation(boolean enabled){}
    @PutMapping("/{id}/activation") public Map<String,String> activate(@PathVariable long id,@RequestBody Activation body){registry.enable(id,body.enabled());return Map.of("message","Mapping updated");}
    @GetMapping("/audit") public List<Map<String,Object>> audit(){return db.queryForList("SELECT request_id,client_id,service_code,instance_id,agent_id,outcome,http_status,occurred_at FROM inbound_write_audit ORDER BY id DESC LIMIT 100");}
}
