package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.service.ApiClientService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WriteClientIntegrationToolsTest {
 @TempDir Path directory;
 @Test void releaseSourceNameIdentifiesClientServiceProfileAndContract(){
  assertThat(WriteSdkRelease.sourceFile(Map.of("client_name","SASCI AOC","title","Agreement Data","contract_version",2),"UAT"))
   .isEqualTo("WriteClientSASCIAOCAgreementDataUATV2.java");
 }
 @Test void publicKeyAndIpValidationRejectUnsafeValues()throws Exception{
  assertThat(WriteClientService.validateIps("127.0.0.1, ::1,127.0.0.1")).isEqualTo("127.0.0.1,::1");
  for(String ip:List.of("example.com","127.1","999.2.3.4","127.0.0.1/8","01.2.3.4"))assertThatThrownBy(()->WriteClientService.validateIps(ip)).isInstanceOf(IllegalArgumentException.class);
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
  WriteClientService.validateKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
  assertThatThrownBy(()->WriteClientService.validateKey("-----BEGIN PRIVATE KEY-----")).isInstanceOf(IllegalArgumentException.class);
  generator.initialize(1024);assertThatThrownBy(()->WriteClientService.validateKey(Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void creatingWriteClientStartsInactiveWithoutReadOrWriteGrants(){
  var db=mock(JdbcTemplate.class);var repo=mock(ApiClientRepository.class);var identities=mock(ApiClientService.class);var client=new ApiClient();client.setClientId(50L);when(identities.registerClient(anyString(),anyString(),anyString())).thenReturn(client);
  var service=new WriteClientService(db,repo,identities,mock(ApplicationEventPublisher.class));
  assertThat(service.create(new WriteClientService.Registration("Uploader","UAT","Agreements"),"manager")).isEqualTo(50);
  assertThat(client.getIsActive()).isFalse();verify(identities).registerClient("Uploader - UAT","Agreements","manager");
  verify(db).update(startsWith("INSERT INTO write_client_profiles"),eq(50L),eq("UAT"),eq("Uploader"),eq("manager"));
  verify(db).update(startsWith("INSERT INTO write_governance_events"),eq("manager"),eq("WRITE_CLIENT_CREATED"),eq("clientId=50"));verifyNoMoreInteractions(db);
 }
 private WriteIntegrationService fixture(){var db=mock(JdbcTemplate.class);String sample="{\"agreement_no\":\"AGR/1\",\"tender_id\":\"001\"}";var spec=JsonWriteDefinition.createSelected("agreement-data",List.of("/agreement_no","/tender_id"),sample,Map.of(),Map.of(),null);
  var row=new LinkedHashMap<String,Object>();row.put("id",3L);row.put("verification_status","VERIFIED");row.put("definition_hash",JsonWriteDefinition.hash(spec));row.put("service_code","agreement-data");row.put("instance_id","Development");row.put("client_id",30L);row.put("agent_id","Dev-01");row.put("protocol_version",1);row.put("contract_version",5);row.put("title","Agreements");row.put("sample_json",sample);row.put("definition_json",JsonWriteDefinition.json(JsonWriteDefinition.describe(spec)));when(db.queryForList(anyString(),eq(3L))).thenReturn(List.of(row));when(db.queryForObject(startsWith("SELECT api_key"),eq(String.class),eq(30L))).thenReturn("test-only-api-key");var service=new WriteIntegrationService(db);WriteAesTest.configureKeys(service);return service;}
 @Test void scopeUsesServiceInstanceAndClientWithoutBusinessKey(){var service=fixture();String sql=service.scopeSql(3,true);assertThat(sql).contains("agreement_data_service_grant","Development","30","DO UPDATE").doesNotContain("business_key","AGR/1");assertThat(service.scopeSql(3,false)).contains("false");assertThat(service.scopeUpgrade(3)).contains("CREATE OR REPLACE FUNCTION","CREATE TABLE IF NOT EXISTS").doesNotContain("DROP TABLE");}
 @Test void sdkIsSelfContainedCompilableAndKeepsVersionsSeparate()throws Exception{var service=fixture();var entries=new HashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(service.sdk(3,"https://doorsapi.doors.test/api/v1")))){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.put(e.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  assertThat(entries.keySet()).contains("WriteClient.java","doors-wallet.properties","sample.json","openapi.json","README.txt","build-doors-write-kit.ps1","readme-enduser-uat.txt","readme-enduser-prod.txt");assertThat(entries.get("doors-wallet.properties")).contains("DOORS_PROTOCOL_VERSION=1","DOORS_CONTRACT_VERSION=5","DOORS_API_KEY=test-only-api-key\n","DOORS_API_BASE_URL=https://doorsapi.doors.test/api/v1");
  assertThat(entries.keySet()).contains("docs/index.html","docs/contract.json","docs/request-schema.json","docs/response-examples.json","docs/04-direct-api.html");assertThat(entries.get("docs/index.html")).doesNotContain("test-only-api-key");
  assertThat(entries.get("doors-wallet.properties")).contains("DOORS_TRUSTSTORE_PATH=\n","DOORS_TRUSTSTORE_PASS=\n","DOORS_TRUSTSTORE_TYPE=PKCS12");
  assertThat(entries.get("README.txt")).contains("DOORS_TRUSTSTORE_PATH","Certificate and hostname verification remain enabled");
  Path source=directory.resolve("WriteClient.java");Files.writeString(source,entries.get("WriteClient.java"));assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",directory.toString(),source.toString())).isZero();
  var api=InboundProtocol.JSON.readTree(entries.get("openapi.json"));assertThat(api.path("info").path("version").asText()).isEqualTo("5");assertThat(api.path("paths").has("/api/v1/inbound/agreement-data")).isTrue();
  try(var loader=new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()})){var clazz=loader.loadClass("WriteClient");var configure=clazz.getDeclaredMethod("configureTruststore",Properties.class);configure.setAccessible(true);
   String[] names={"javax.net.ssl.trustStore","javax.net.ssl.trustStorePassword","javax.net.ssl.trustStoreType"};var old=new HashMap<String,String>();for(String name:names)old.put(name,System.getProperty(name));
   try{System.setProperty(names[0],"existing-jvm-store");configure.invoke(null,new Properties());assertThat(System.getProperty(names[0])).isEqualTo("existing-jvm-store");
    var options=new Properties();options.setProperty("DOORS_TRUSTSTORE_PATH",directory.resolve("missing.p12").toString());assertThatThrownBy(()->configure.invoke(null,options)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    Path store=directory.resolve("test.p12");Files.write(store,new byte[0]);options.setProperty("DOORS_TRUSTSTORE_PATH",store.toString());options.setProperty("DOORS_TRUSTSTORE_PASS","test-pass");configure.invoke(null,options);assertThat(System.getProperty(names[0])).isEqualTo(store.toAbsolutePath().toString());assertThat(System.getProperty(names[1])).isEqualTo("test-pass");assertThat(System.getProperty(names[2])).isEqualTo("PKCS12");
   }finally{for(String name:names){if(old.get(name)==null)System.clearProperty(name);else System.setProperty(name,old.get(name));}}
   var method=clazz.getDeclaredMethod("requestId",String.class);method.setAccessible(true);String generated=(String)method.invoke(null,new Object[]{null});assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);assertThatThrownBy(()->method.invoke(null,"ABCDEFGHIJILMNOP")).hasRootCauseMessage("Invalid request UUID. Omit the third argument to generate one, or use PowerShell: [guid]::NewGuid().ToString(). For a retry, reuse the original UUID; this is not the API key.");}

 }

 private Map<String,byte[]> unpack(byte[] content)throws Exception{var entries=new HashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(content))){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.put(e.getName(),zip.readAllBytes());}return entries;}
 @Test void finishedProductionReleaseNeverContainsUatIdentityOrInstructions()throws Exception{
  var service=fixture();var entries=unpack(service.sdk(3,"https://doorsapi.doors.test/api/v1","PROD"));
  assertThat(entries.keySet()).contains("readme-enduser-prod.txt","docs/index.html").doesNotContain("readme-enduser-uat.txt","build-doors-write-kit.ps1","README.txt","doors/security/doors-identity.p12");
  assertThat(new String(entries.get("readme-enduser-prod.txt"))).contains("YOUR production P12","client_public.pem","Run: java WriteClient.java sample.json\r\n","Automatic versioning").doesNotContain("-Profile UAT");
  var props=new Properties();props.load(new ByteArrayInputStream(entries.get("doors-wallet.properties")));assertThat(props.getProperty("DOORS_WALLET_PASS")).isEmpty();assertThat(props.getProperty("DOORS_WALLET_PATH")).isEqualTo("~/doors/security/doors-identity.p12");
  assertThatThrownBy(()->service.sdk(3,"","OTHER")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.sdk(3,"","UAT")).hasMessageContaining("not configured");
 }
 @Test void finishedUatReleaseContainsUsableSelectedKeyAndMatchingCertificate()throws Exception{
  var keyfile=directory.resolve("test.p12");var settings=directory.resolve("wallet.properties");
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias","test-uat","-keyalg","RSA","-keysize","2048","-dname","CN=UAT test","-validity","2","-storetype","PKCS12","-keystore",keyfile.toString(),"-storepass","fixture-only-pass","-noprompt").redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
  Files.writeString(settings,"DOORS_WALLET_PASS=fixture-only-pass\nDOORS_WALLET_ALIAS=test-uat\n");var service=fixture();
  var details=new LinkedHashMap<>(service.details(3));details.put("encryption",Map.of("kid","test-key","publicKey",WriteCrypto.pem(WriteAesTest.pair().getPublic())));var entries=unpack(WriteSdkRelease.create(service.sdk(3,""),details,"","UAT",keyfile.toString(),settings.toString()));
  assertThat(entries.keySet()).contains("doors/security/doors-identity.p12","doors/security/client_public.pem","readme-enduser-uat.txt").doesNotContain("readme-enduser-prod.txt","build-doors-write-kit.ps1");
  var props=new Properties();props.load(new ByteArrayInputStream(entries.get("doors-wallet.properties")));var store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(entries.get("doors/security/doors-identity.p12")),props.getProperty("DOORS_WALLET_PASS").toCharArray());
  assertThat(store.getKey(props.getProperty("DOORS_WALLET_ALIAS"),props.getProperty("DOORS_WALLET_PASS").toCharArray())).isInstanceOf(PrivateKey.class);
  var cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(entries.get("doors/security/client_public.pem")));assertThat(cert.getEncoded()).isEqualTo(store.getCertificate("doors-uat").getEncoded());
  assertThat(new String(entries.get("docs/01-sdk-guide.html"))).contains("UAT package").doesNotContain("fixture-only-pass");
  var trust=KeyStore.getInstance("PKCS12");trust.load(null,"private-source-pass".toCharArray());trust.setCertificateEntry("ca",cert);
  trust.setKeyEntry("must-not-export",store.getKey("doors-uat",props.getProperty("DOORS_WALLET_PASS").toCharArray()),"private-source-pass".toCharArray(),store.getCertificateChain("doors-uat"));
  try(var out=Files.newOutputStream(directory.resolve("trust.p12"))){trust.store(out,"private-source-pass".toCharArray());}
  Files.writeString(settings,"DOORS_TRUSTSTORE_PATH=trust.p12\nDOORS_TRUSTSTORE_PASS=private-source-pass\n",java.nio.file.StandardOpenOption.APPEND);
  var secured=unpack(WriteSdkRelease.create(service.sdk(3,""),details,"","UAT",keyfile.toString(),settings.toString()));
  var wp=new Properties();wp.load(new ByteArrayInputStream(secured.get("doors-wallet.properties")));
  assertThat(wp.getProperty("DOORS_TRUSTSTORE_PATH")).isEqualTo("doors/security/https-truststore.p12");
  var exported=KeyStore.getInstance("PKCS12");exported.load(new ByteArrayInputStream(secured.get(wp.getProperty("DOORS_TRUSTSTORE_PATH"))),wp.getProperty("DOORS_TRUSTSTORE_PASS").toCharArray());
  assertThat(exported.size()).isEqualTo(1);assertThat(exported.isCertificateEntry("ca-1")).isTrue();
  var prod=unpack(WriteSdkRelease.create(service.sdk(3,""),details,"","PROD",keyfile.toString(),settings.toString()));
  assertThat(prod).doesNotContainKey("doors/security/https-truststore.p12");
  Files.writeString(settings,"DOORS_TRUSTSTORE_PATH=missing.p12\n",java.nio.file.StandardOpenOption.APPEND);
  assertThatThrownBy(()->WriteSdkRelease.create(service.sdk(3,""),details,"","UAT",keyfile.toString(),settings.toString())).hasMessageContaining("truststore");

 }
 @Test void versionSelectionRejectsMismatchButAllowsLegacyOmission(){var db=mock(JdbcTemplate.class);when(db.queryForMap(anyString(),eq(3L))).thenReturn(Map.of("protocol_version",1,"contract_version",5));var registry=new InboundRegistry(db,null,null);var route=new InboundRegistry.Route(3,"agreement-data","Development","Dev-01",30,true);registry.validateVersions(route,1,5);registry.validateVersions(route,null,null);assertThatThrownBy(()->registry.validateVersions(route,2,5)).hasMessageContaining("protocol");assertThatThrownBy(()->registry.validateVersions(route,1,4)).hasMessageContaining("contract");}
}
