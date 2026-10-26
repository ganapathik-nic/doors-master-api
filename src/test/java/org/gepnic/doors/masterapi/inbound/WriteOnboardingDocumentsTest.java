package org.gepnic.doors.masterapi.inbound;
import org.gepnic.doors.inbound.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WriteOnboardingDocumentsTest {
 static Map<String,Object> definition(int storage){
  String body="{\"agreement_no\":\"AGR-1\",\"tender_id\":\"001\",\"agreement_date\":\"2026-10-22\",\"amount\":\"1250.50\",\"party\":{\"name\":\"Example contractor\"}}";
  var fields=List.of(new JsonWriteDefinition.Field("/agreement_no","agreement_no","text"),new JsonWriteDefinition.Field("/tender_id","tender_id","text"),new JsonWriteDefinition.Field("/agreement_date","agreement_date","date"),new JsonWriteDefinition.Field("/amount","amount","numeric"),new JsonWriteDefinition.Field("/party/name","party_name","varchar(120)"));
  var spec=new JsonWriteDefinition.Spec("agreement-example",List.of("/agreement_no","/tender_id"),fields,storage);
  var d=new LinkedHashMap<String,Object>();d.put("id",88);d.put("client_id",303);d.put("service_code",spec.service());d.put("title","Example Agreement onboarding");d.put("instance_id","ExampleUAT");d.put("agent_id","Example-Agent");d.put("protocol_version",1);d.put("contract_version",17);d.put("verification_status","VERIFIED");d.put("verified_at","2026-10-22T12:00:00Z");d.put("definition_hash",JsonWriteDefinition.hash(spec));d.put("definition_json",JsonWriteDefinition.json(JsonWriteDefinition.describe(spec)));d.put("enabled",false);d.put("max_versions",storage==3?3:null);d.put("sample_json",body);return d;
 }
 @Test void documentsUsePinnedFieldsVersionsLimitsAndNoCredentials()throws Exception{
  var d=definition(3);var files=WriteOnboardingDocuments.create(d,"https://example.invalid/doors/api/v1");
  assertThat(files).hasSize(9);
  var contract=InboundProtocol.JSON.readTree(files.get("docs/contract.json"));assertThat(contract.path("contract_version").asInt()).isEqualTo(17);assertThat(contract.path("max_versions").asInt()).isEqualTo(3);assertThat(contract.path("recordKey")).hasSize(2);assertThat(contract.path("automaticVersioning").asBoolean()).isTrue();
  assertThat(files.get("docs/02-json-contract.html")).contains("varchar(120)","/party/name","party_name","agreement_date","numeric");
  assertThat(files.get("docs/04-direct-api.html")).contains("RSASSA-PKCS1-v1_5","AES-256-GCM","X-DOORS-Expected-Version: -1","/doors/api/v1/inbound/agreement-example");
  var schema=InboundProtocol.JSON.readTree(files.get("docs/request-schema.json"));assertThat(schema.path("required")).hasSize(2);assertThat(schema.path("properties").path("agreement_date").path("format").asText()).isEqualTo("date");assertThat(schema.path("properties").path("party").path("properties").path("name").path("maxLength").asInt()).isEqualTo(120);
  var examples=InboundProtocol.JSON.readTree(files.get("docs/response-examples.json"));assertThat(examples.has("unchanged")).isTrue();assertThat(examples.path("versionLimit").path("body").path("code").asText()).isEqualTo("VERSION_LIMIT_REACHED");
  var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq(88L))).thenReturn(List.of(d));when(db.queryForObject(startsWith("SELECT api_key"),eq(String.class),eq(303))).thenReturn("fixture-only-key");Files.write(Path.of("target/onboarding-sdk-fixture.zip"),new WriteIntegrationService(db).sdk(88,"https://example.invalid/doors/api/v1"));
  Path preview=Path.of("target/onboarding-preview");Files.createDirectories(preview.resolve("docs"));for(var file:files.entrySet())Files.writeString(preview.resolve(file.getKey()),file.getValue());Files.writeString(preview.resolve("sample.json"),d.get("sample_json").toString());Files.writeString(preview.resolve("openapi.json"),"{}");
 }
 @Test void legacyInstructionsAndExamplesDoNotPromiseAutomaticMode()throws Exception{
  var files=WriteOnboardingDocuments.create(definition(2),"");assertThat(files.get("docs/01-sdk-guide.html")).contains("sample.json 0","explicit versions");
  var examples=InboundProtocol.JSON.readTree(files.get("docs/response-examples.json"));assertThat(examples.has("unchanged")).isFalse();assertThat(examples.has("versionLimit")).isFalse();assertThat(files.get("docs/04-direct-api.html")).contains("Automatic -1 is not supported");
 }
 @Test void untrustedTitlesAndEndpointsCannotBecomeHtml()throws Exception{
  var d=definition(3);d.put("title","<script>alert(1)</script>");var files=WriteOnboardingDocuments.create(d,"https://example.invalid/\"<img>/api/v1");for(var html:files.entrySet())if(html.getKey().endsWith(".html")){assertThat(html.getValue()).doesNotContain("<script>","<img>");assertThat(html.getValue()).contains("&lt;script&gt;");}
 }
 @Test void sdkRequiresVerifiedMatchingDefinitionBeforeReadingClientKey()throws Exception{
  var db=mock(JdbcTemplate.class);var d=definition(3);d.put("verification_status","PENDING");when(db.queryForList(anyString(),eq(88L))).thenReturn(List.of(d));var service=new WriteIntegrationService(db);
  assertThatThrownBy(()->service.sdk(88)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Verify");verify(db,never()).queryForObject(startsWith("SELECT api_key"),eq(String.class),any());
  d.put("verification_status","VERIFIED");d.put("definition_hash","wrong");assertThatThrownBy(()->service.sdk(88)).hasMessageContaining("Verify");
 }
}
