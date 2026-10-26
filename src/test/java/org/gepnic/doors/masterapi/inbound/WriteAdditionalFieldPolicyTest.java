package org.gepnic.doors.masterapi.inbound;

import org.gepnic.doors.inbound.JsonWriteDefinition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteAdditionalFieldPolicyTest {
 @Test void newContractsRejectUnknownFieldsButAllowApprovedPayloadOnlyFields(){
  String sample="{\"id\":\"A1\",\"note\":\"approved payload-only\"}";
  var spec=JsonWriteDefinition.createSelected("strict-data",List.of("/id"),sample,Map.of(),Map.of(),List.of("/id"),JsonWriteDefinition.REJECT_UNAPPROVED);
  assertDoesNotThrow(()->JsonWriteDefinition.validate(spec,sample));
  var error=assertThrows(JsonWriteDefinition.UnknownJsonFieldsException.class,()->JsonWriteDefinition.validate(spec,"{\"id\":\"A1\",\"note\":\"ok\",\"unexpected\":1}"));
  assertEquals(List.of("/unexpected"),error.paths());
  assertEquals(false,WriteOnboardingDocuments.requestSchema(spec).get("additionalProperties"));
 }

 @Test void storedContractsWithoutPolicyRemainOpen(){
  var legacy=new JsonWriteDefinition.Spec("legacy-data",List.of("/id"),List.of(new JsonWriteDefinition.Field("/id","id","text")),3);
  assertEquals(JsonWriteDefinition.ALLOW_PAYLOAD_ONLY,JsonWriteDefinition.additionalFieldPolicy(legacy));
  assertDoesNotThrow(()->JsonWriteDefinition.validate(legacy,"{\"id\":\"A1\",\"future\":true}"));
 }
}
