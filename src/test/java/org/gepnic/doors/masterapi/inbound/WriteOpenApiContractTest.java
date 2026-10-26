package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.io.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class WriteOpenApiContractTest {
 @Test void openapiAndBothSdkProfilesUseThePinnedFieldSchemaWithSeparateExample()throws Exception{
  var d=WriteOnboardingDocumentsTest.definition(3);var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq(88L))).thenReturn(List.of(d));when(db.queryForObject(startsWith("SELECT api_key"),eq(String.class),eq(303))).thenReturn("fixture-key");var service=new WriteIntegrationService(db);WriteAesTest.configureKeys(service);
  var open=InboundProtocol.JSON.valueToTree(service.openapi(88));assertThat(open.path("openapi").asText()).isEqualTo("3.1.0");var media=open.at("/paths/~1api~1v1~1inbound~1agreement-example/post/requestBody/content/application~1json");var schema=media.path("x-doors-plaintext-schema");
  assertThat(media.path("x-doors-plaintext-example")).isEqualTo(InboundProtocol.JSON.readTree(d.get("sample_json").toString()));assertThat(schema.path("properties").path("agreement_date").path("format").asText()).isEqualTo("date");assertThat(schema.at("/properties/party/properties/name/maxLength").asInt()).isEqualTo(120);assertThat(schema.path("required").toString()).isEqualTo("[\"agreement_no\",\"tender_id\"]");assertThat(schema.path("additionalProperties").asBoolean()).isTrue();
  var docs=WriteOnboardingDocuments.create(d,"");assertThat(schema).isEqualTo(InboundProtocol.JSON.readTree(docs.get("docs/request-schema.json")));
  try(var zip=new ZipInputStream(new ByteArrayInputStream(service.sdk(88,"https://example.invalid/api/v1","PROD")))){boolean found=false;for(ZipEntry e;(e=zip.getNextEntry())!=null;)if(e.getName().equals("openapi.json")){assertThat(InboundProtocol.JSON.readTree(zip.readAllBytes())).isEqualTo(open);found=true;}assertThat(found).isTrue();}
  Path dir=Path.of("target/write-openapi-contract-test");Files.createDirectories(dir);Files.writeString(dir.resolve("openapi.json"),open.toPrettyString());Files.writeString(dir.resolve("sample.json"),d.get("sample_json").toString());
 }
 @Test void nestedKeysAndTypesHaveUsableConstraints()throws Exception{
  var spec=new JsonWriteDefinition.Spec("typed-service",List.of("/party/id"),List.of(new JsonWriteDefinition.Field("/party/id","party_id","varchar(30)"),new JsonWriteDefinition.Field("/count","count_value","integer"),new JsonWriteDefinition.Field("/tags","tags","integer[]"),new JsonWriteDefinition.Field("/enabled","enabled","boolean"),new JsonWriteDefinition.Field("/external_id","external_id","uuid"),new JsonWriteDefinition.Field("/when","when_value","timestamp with time zone")),3);
  var schema=InboundProtocol.JSON.valueToTree(WriteOnboardingDocuments.requestSchema(spec));assertThat(schema.path("required").toString()).isEqualTo("[\"party\"]");assertThat(schema.at("/properties/party/type").asText()).isEqualTo("object");assertThat(schema.at("/properties/party/required").toString()).isEqualTo("[\"id\"]");assertThat(schema.at("/properties/party/properties/id/maxLength").asInt()).isEqualTo(30);assertThat(schema.at("/properties/count/anyOf/0/type").asText()).isEqualTo("integer");assertThat(schema.at("/properties/tags/items/anyOf/0/type").asText()).isEqualTo("integer");assertThat(schema.at("/properties/external_id/format").asText()).isEqualTo("uuid");assertThat(schema.at("/properties/when/format").asText()).isEqualTo("date-time");
  Path dir=Path.of("target/write-openapi-contract-test");Files.createDirectories(dir);Files.writeString(dir.resolve("typed-schema.json"),schema.toPrettyString());
 }
}
