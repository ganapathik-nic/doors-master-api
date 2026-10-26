package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.gepnic.doors.inbound.JsonWriteDefinition;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class JsonWriteColumnNamesTest {
 @Test void customColumnsAreValidatedAndIncludedInSql(){
  String sample="{\"id\":\"1\",\"party\":{\"name\":\"A\"}}";
  var spec=JsonWriteDefinition.create("agreement-data",List.of("/id"),sample,Map.of(),Map.of("/party/name","vendor_name"));
  assertThat(JsonWriteDefinition.ddl(spec)).contains("vendor_name");
  for(String invalid:List.of("payload","id","Bad Name","x;drop table y",""))assertThatThrownBy(()->JsonWriteDefinition.create("agreement-data",List.of("/id"),sample,Map.of(),Map.of("/party/name",invalid))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void readableNestedNamesAndDeterministicCollisions(){
  String sample="{\"id\":\"1\",\"party_1\":{\"name\":\"A\",\"type\":\"Vendor\"},\"party_2\":{\"name\":\"B\"},\"party_1_name\":\"Other\",\"payload\":{},\"1field\":true,\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\":1}";
  var fields=JsonWriteDefinition.fields(sample);
  assertThat(fields).contains(new JsonWriteDefinition.Field("/party_1/name","party_1_name","text"),new JsonWriteDefinition.Field("/party_1/type","party_1_type","text"),new JsonWriteDefinition.Field("/party_2/name","party_2_name","text"));
  assertThat(fields.stream().map(JsonWriteDefinition.Field::column).toList()).doesNotHaveDuplicates().doesNotContain("payload").allMatch(c->c.matches("[a-z][a-z0-9_]{0,39}"));
  assertThat(JsonWriteDefinition.fields(sample)).isEqualTo(fields);
  JsonWriteDefinition.create("agreement-data",List.of("/id"),sample);
 }
 @Test void savedHashedColumnsRemainValidAndUnchanged(){
  var spec=new JsonWriteDefinition.Spec("agreement-data",List.of("/id"),List.of(new JsonWriteDefinition.Field("/id","id","text"),new JsonWriteDefinition.Field("/party/name","field_24cd0e1ca736","text")));
  JsonWriteDefinition.validate(spec,"{\"id\":\"1\",\"party\":{\"name\":\"A\"}}");
  assertThat(JsonWriteDefinition.ddl(spec)).contains("field_24cd0e1ca736");
 }

 @org.junit.jupiter.api.Test void optionalScalarColumnsKeepOneCompletePayload(){
  String body="{\"id\":\"A1\",\"party_1\":{\"name\":\"NIC\",\"type\":\"Vendor\"},\"items\":[1,2]}";
  var fields=JsonWriteDefinition.extractableFields(body);
  org.assertj.core.api.Assertions.assertThat(fields).extracting(JsonWriteDefinition.Field::path).containsExactly("/id","/party_1/name","/party_1/type");
  var spec=JsonWriteDefinition.createSelected("agreement-data",java.util.List.of("/id"),body,null,null,java.util.List.of("/id","/party_1/name"));
  org.assertj.core.api.Assertions.assertThat(spec.fields()).extracting(JsonWriteDefinition.Field::column).containsExactly("id","party_1_name");
  org.assertj.core.api.Assertions.assertThat(JsonWriteDefinition.validate(spec,body).at("/party_1/type").asText()).isEqualTo("Vendor");
  org.assertj.core.api.Assertions.assertThat(JsonWriteDefinition.ddl(spec)).contains("payload json NOT NULL").doesNotContain("\"party_1\" json","\"party_1_type\"");
  org.assertj.core.api.Assertions.assertThatThrownBy(()->JsonWriteDefinition.createSelected("agreement-data",java.util.List.of("/id"),body,null,null,java.util.List.of("/party_1/name"))).isInstanceOf(IllegalArgumentException.class);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->JsonWriteDefinition.createSelected("agreement-data",java.util.List.of("/id"),body,java.util.Map.of("/party_1/name","json"),null,null)).isInstanceOf(IllegalArgumentException.class);
 }
}
