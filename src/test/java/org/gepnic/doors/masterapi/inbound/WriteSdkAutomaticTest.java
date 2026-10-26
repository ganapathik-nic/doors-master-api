package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class WriteSdkAutomaticTest {
 @TempDir Path dir;
 @Test void simpleCommandRetainsPendingUuidAndLegacyCommandStillSigns()throws Exception{
  String javaBin=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
  var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-storetype","PKCS12","-keystore",dir.resolve("test.p12").toString(),"-storepass","test-password","-keypass","test-password","-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).start();keytool.getInputStream().readAllBytes();assertEquals(0,keytool.waitFor());
  KeyStore ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(dir.resolve("test.p12"))){ks.load(in,"test-password".toCharArray());}var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,"test-password".toCharArray());var ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,null);
  var server=HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl));var ids=new java.util.concurrent.CopyOnWriteArrayList<String>();var versions=new java.util.concurrent.CopyOnWriteArrayList<String>();var errors=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
  server.createContext("/api/v1/inbound/test-data",e->{try{byte[] body=e.getRequestBody().readAllBytes();String id=e.getRequestHeaders().getFirst("Idempotency-Key"),expected=e.getRequestHeaders().getFirst("X-DOORS-Expected-Version");ids.add(id);versions.add(expected);
   String text="DOORS-INBOUND-1\ntest-data\nDevelopment\n"+id+"\n1\n"+expected+"\n"+e.getRequestHeaders().getFirst("X-DOORS-Timestamp")+"\n"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(ks.getCertificate("test"));verifier.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(verifier.verify(Base64.getDecoder().decode(e.getRequestHeaders().getFirst("X-DOORS-Signature"))));
   int status=ids.size()==1?504:201;byte[] response=(status==504?"{\"code\":\"WRITE_RESULT_UNKNOWN\"}":"{\"outcome\":\"STORED\"}").getBytes();e.sendResponseHeaders(status,response.length);e.getResponseBody().write(response);
  }catch(Throwable t){errors.add(t);}finally{e.close();}});server.start();
  try{
   try(var in=getClass().getResourceAsStream("/inbound/sdk/WriteClient.java")){Files.copy(in,dir.resolve("WriteClient.java"));}assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),dir.resolve("WriteClient.java").toString()));
   Files.writeString(dir.resolve("doors-wallet.properties"),"DOORS_API_BASE_URL=https://localhost:"+server.getAddress().getPort()+"/api/v1\nDOORS_SERVICE=test-data\nDOORS_INSTANCE=Development\nDOORS_PROTOCOL_VERSION=1\nDOORS_CONTRACT_VERSION=1\nDOORS_AUTOMATIC_VERSIONING=true\nDOORS_API_KEY=test-key\nDOORS_WALLET_PATH=test.p12\nDOORS_WALLET_PASS=test-password\nDOORS_WALLET_ALIAS=test\nDOORS_TRUSTSTORE_PATH=test.p12\nDOORS_TRUSTSTORE_PASS=test-password\n");Files.writeString(dir.resolve("sample.json"),"{\"id\":\"one\"}");
   assertNotEquals(0,run(javaBin,"sample.json"));assertEquals(1,ids.size());
   Files.writeString(dir.resolve("sample.json"),"{\"id\":\"different\"}");assertNotEquals(0,run(javaBin,"sample.json"));assertEquals(1,ids.size());
   Files.writeString(dir.resolve("sample.json"),"{\"id\":\"one\"}");assertEquals(0,run(javaBin,"sample.json"));assertEquals(ids.get(0),ids.get(1));
   assertEquals(0,run(javaBin,"sample.json"));assertNotEquals(ids.get(1),ids.get(2));
   String explicit=UUID.randomUUID().toString();assertEquals(0,run(javaBin,"sample.json","0",explicit));assertEquals(explicit,ids.get(3));assertEquals(List.of("-1","-1","-1","0"),versions);assertTrue(errors.isEmpty(),errors.toString());
  }finally{server.stop(0);}
 }
 int run(String java,String... args)throws Exception{var command=new ArrayList<>(List.of(java,"-cp",dir.toString(),"WriteClient"));command.addAll(List.of(args));var builder=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);builder.environment().keySet().removeIf(k->k.startsWith("DOORS_")||k.equals("JAVA_TOOL_OPTIONS"));var process=builder.start();String output=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(50,TimeUnit.SECONDS),output);return process.exitValue();}
}
