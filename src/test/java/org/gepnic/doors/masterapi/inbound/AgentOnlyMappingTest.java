package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentOnlyMappingTest {
 @Test @EnabledIfEnvironmentVariable(named="DOORS_INBOUND_TEST_JDBC",matches=".+") void batchMappingPreservesLegacyAndIsAtomic(){
  String url=System.getenv("DOORS_INBOUND_TEST_JDBC"),ns="mapping_test_"+UUID.randomUUID().toString().replace("-","");
  var owner=new JdbcTemplate(new DriverManagerDataSource(url,"doors_test",""));owner.execute("CREATE SCHEMA "+ns);
  try{
   var ds=new DriverManagerDataSource(url+"?currentSchema="+ns,"doors_test","");var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
   db.execute("CREATE TABLE agents(agent_id text primary key,is_active boolean)");
   db.execute("CREATE TABLE write_contracts(id bigint primary key,service_code text,status text,definition_hash text,definition_json jsonb)");
   db.execute("CREATE TABLE write_deployments(id bigserial primary key,contract_id bigint,instance_id text,agent_id text,created_by text,agent_routing boolean default false,unique(contract_id,instance_id))");
   db.execute("CREATE TABLE inbound_write_routes(instance_id text,agent_id text)");
   db.execute("CREATE TABLE write_governance_events(contract_id bigint,actor text,action text,detail text)");
   var spec=JsonWriteDefinition.create("mapping-test",List.of("/id"),"{\"id\":\"one\"}");
   for(long id:List.of(1L,2L))db.update("INSERT INTO write_contracts VALUES(?,'mapping-test','APPROVED',?,?::jsonb)",id,JsonWriteDefinition.hash(spec),JsonWriteDefinition.json(JsonWriteDefinition.describe(spec)));
   db.execute("INSERT INTO agents VALUES('Dev-01',true),('Dev-02',true)");
   db.execute("INSERT INTO write_deployments(contract_id,instance_id,agent_id,created_by) VALUES(1,'Development','Dev-01','old')");
   var service=new WriteGovernanceService(db,mock(WriteAgentControl.class));
   var ids=tx.execute(status->service.mapAgents(new WriteGovernanceService.AgentMapping(1,List.of("Dev-01","Dev-02")),"tester"));assertEquals(2,ids.size());
   assertEquals("Development",db.queryForObject("SELECT instance_id FROM write_deployments WHERE agent_id='Dev-01'",String.class));
   assertFalse(db.queryForObject("SELECT agent_routing FROM write_deployments WHERE agent_id='Dev-01'",Boolean.class));
   assertTrue(db.queryForObject("SELECT agent_routing FROM write_deployments WHERE agent_id='Dev-02'",Boolean.class));
   assertEquals(ids,tx.execute(status->service.mapAgents(new WriteGovernanceService.AgentMapping(1,List.of("Dev-01","Dev-02")),"tester")));
   assertThrows(IllegalArgumentException.class,()->tx.execute(status->service.mapAgents(new WriteGovernanceService.AgentMapping(2,List.of("Dev-02","missing")),"tester")));
   assertEquals(0,db.queryForObject("SELECT count(*) FROM write_deployments WHERE contract_id=2",Integer.class));
   tx.execute(status->service.mapAgents(new WriteGovernanceService.AgentMapping(2,List.of("Dev-01","Dev-02")),"tester"));
   assertEquals(2,db.queryForObject("SELECT count(*) FROM write_deployments WHERE agent_id='Dev-02' AND agent_routing",Integer.class));
  }finally{owner.execute("DROP SCHEMA "+ns+" CASCADE");}
 }
}
