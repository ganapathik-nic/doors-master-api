package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.gepnic.doors.inbound.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WriteVersionLimitTest {
 @Test void limitsAreMappingScopedAndLegacyDefinitionsRequireApproval(){
  var db=mock(JdbcTemplate.class);var registry=new InboundRegistry(db,null,null);
  var spec=JsonWriteDefinition.createSelected("limit-data",List.of("/id"),"{\"id\":\"one\"}",Map.of(),Map.of(),null);
  when(db.queryForList(anyString(),eq(7L))).thenReturn(List.of(Map.of("definition_json",JsonWriteDefinition.json(JsonWriteDefinition.describe(spec)))));
  registry.setMaxVersions(7,3L);verify(db).update("UPDATE inbound_write_routes SET max_versions=? WHERE id=?",3L,7L);
  registry.setMaxVersions(7,null);verify(db).update("UPDATE inbound_write_routes SET max_versions=? WHERE id=?",null,7L);
  assertThrows(IllegalArgumentException.class,()->registry.setMaxVersions(7,0L));assertThrows(IllegalArgumentException.class,()->registry.setMaxVersions(7,1000001L));
  var legacy=new JsonWriteDefinition.Spec(spec.service(),spec.businessKey(),spec.fields(),2);
  when(db.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("definition_json",JsonWriteDefinition.json(JsonWriteDefinition.describe(legacy)))));
  assertThrows(IllegalStateException.class,()->registry.setMaxVersions(8,3L));
 }
}
