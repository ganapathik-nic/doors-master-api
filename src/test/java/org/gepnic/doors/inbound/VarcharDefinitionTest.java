package org.gepnic.doors.inbound;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VarcharDefinitionTest {
 @Test void boundedTextIsValidatedAndIncludedInSql(){
  var spec=JsonWriteDefinition.create("length-test",List.of("/id"),"{\"id\":\"abc\"}",Map.of("/id","varchar(3)"));
  assertTrue(JsonWriteDefinition.recordColumns(spec).contains("varchar(3)"));
  assertThrows(IllegalArgumentException.class,()->JsonWriteDefinition.validate(spec,"{\"id\":\"abcd\"}"));
  assertDoesNotThrow(()->JsonWriteDefinition.validate(spec,"{\"id\":\"😀ab\"}"));
  for(String type:List.of("varchar(0)","varchar(-1)","varchar(10485761)","varchar(3); DROP TABLE x","varchar(999999999999)"))assertFalse(JsonWriteDefinition.supported(type));
  assertTrue(JsonWriteDefinition.supported("varchar"));
  assertTrue(JsonWriteDefinition.supported("varchar(10485760)"));
 }
}
