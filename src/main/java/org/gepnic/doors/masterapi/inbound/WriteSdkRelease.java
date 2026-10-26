package org.gepnic.doors.masterapi.inbound;

import org.gepnic.doors.inbound.JsonWriteDefinition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Finished client releases. Only explicit UAT configuration can supply a test private key. */
final class WriteSdkRelease {
 static byte[] create(byte[] preparation,Map<String,Object> details,String base,String profile,String identityPath,String settingsPath)throws Exception {
  if(!Set.of("UAT","PROD").contains(profile))throw new IllegalArgumentException("Choose UAT or PROD SDK");
  var files=new LinkedHashMap<String,byte[]>();
  try(var in=new ZipInputStream(new ByteArrayInputStream(preparation),StandardCharsets.UTF_8)){
   for(ZipEntry e;(e=in.getNextEntry())!=null;)if(Set.of("WriteClient.java","sample.json","openapi.json","doors-wallet.properties").contains(e.getName()))files.put(e.getName(),in.readAllBytes());
  }
  var wallet=new Properties();wallet.load(new StringReader(new String(files.get("doors-wallet.properties"),StandardCharsets.UTF_8)));
  wallet.setProperty("DOORS_SDK_PROFILE",profile);
  @SuppressWarnings("unchecked") var encryption=(Map<String,String>)details.get("encryption");
  if(encryption==null)throw new IllegalArgumentException("DOORS public encryption key is required for SDK generation");
  {wallet.setProperty("DOORS_ENCRYPTION",WriteCrypto.MODE);wallet.setProperty("DOORS_SERVER_KEY_ID",encryption.get("kid"));wallet.setProperty("DOORS_SERVER_PUBLIC_KEY_PATH","doors-server-public.pem");files.put("doors-server-public.pem",encryption.get("publicKey").getBytes(StandardCharsets.UTF_8));}
  wallet.setProperty("DOORS_WALLET_PATH","~/doors/security/doors-identity.p12");
  wallet.setProperty("DOORS_WALLET_PASS","");wallet.setProperty("DOORS_WALLET_ALIAS","");
  boolean bundledTrust=false;
  if(profile.equals("UAT")){
   if(identityPath.isBlank()||settingsPath.isBlank())throw new IllegalArgumentException("UAT SDK identity is not configured. Administrator: set doors.write-sdk.uat-identity-path and doors.write-sdk.uat-wallet-properties to the dedicated test identity and wallet settings files.");
   try {
    var settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(settingsPath),StandardCharsets.UTF_8)){settings.load(reader);}
    bundledTrust=addUatTrust(files,wallet,settings,Path.of(settingsPath));
    String password=settings.getProperty("DOORS_WALLET_PASS");if(password==null)throw new GeneralSecurityException();
    if(Files.size(Path.of(identityPath))>1048576)throw new GeneralSecurityException();
    var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(Path.of(identityPath))){store.load(input,password.toCharArray());}
    var keys=new ArrayList<String>();for(var aliases=store.aliases();aliases.hasMoreElements();){String alias=aliases.nextElement();if(store.isKeyEntry(alias))keys.add(alias);}
    String alias=settings.getProperty("DOORS_WALLET_ALIAS","").strip();if(alias.isEmpty()&&keys.size()==1)alias=keys.getFirst();
    var key=store.getKey(alias,password.toCharArray());var certificate=store.getCertificate(alias);
    if(!(key instanceof PrivateKey)||!"RSA".equals(key.getAlgorithm())||certificate==null)throw new GeneralSecurityException();
    // Export only the selected identity; never copy other keys that might share the source wallet.
    var releaseStore=KeyStore.getInstance("PKCS12");releaseStore.load(null,password.toCharArray());releaseStore.setKeyEntry("doors-uat",key,password.toCharArray(),store.getCertificateChain(alias));
    var bytes=new ByteArrayOutputStream();releaseStore.store(bytes,password.toCharArray());files.put("doors/security/doors-identity.p12",bytes.toByteArray());
    String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(certificate.getEncoded())+"\n-----END CERTIFICATE-----\n";
    files.put("doors/security/client_public.pem",pem.getBytes(StandardCharsets.UTF_8));
    wallet.setProperty("DOORS_WALLET_PATH","doors/security/doors-identity.p12");wallet.setProperty("DOORS_WALLET_ALIAS","doors-uat");wallet.setProperty("DOORS_WALLET_PASS",password);
   }catch(Exception e){throw new IllegalArgumentException("Configured UAT identity could not be loaded. Check the test P12, wallet password, private-key alias and configured UAT HTTPS truststore. No SDK was generated.");}
  }
  var walletText=new StringWriter();wallet.store(walletText,"DOORS "+profile+" SDK - keep this file private; environment variables override these values");files.put("doors-wallet.properties",walletText.toString().getBytes(StandardCharsets.UTF_8));
  var metadata=new LinkedHashMap<>(details);metadata.put("sdk_profile",profile);
  for(var e:WriteOnboardingDocuments.create(metadata,base).entrySet())files.put(e.getKey(),e.getValue().getBytes(StandardCharsets.UTF_8));
  boolean auto=JsonWriteDefinition.automatic(JsonWriteDefinition.from(details.get("definition_json")));
  String readme=readme(details,profile,auto,bundledTrust);
  files.put("readme-enduser-"+profile.toLowerCase(Locale.ROOT)+".txt",readme.getBytes(StandardCharsets.UTF_8));
  var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return output.toByteArray();
 }
 private static String readme(Map<String,Object> d,String profile,boolean automatic,boolean trust){
  String command="java WriteClient.java sample.json"+(automatic?"":" 0");
  String text="DOORS WRITE SDK - "+profile+" - Java 21+\n"
   +"Client: "+d.get("client_id")+"; service: "+d.get("service_code")+"; contract: "+d.get("contract_version")+"\n\n"
   +"1. DOWNLOAD AND VERIFY\n"
   +"Download the ZIP and its matching .zip.sha256 file from the password-protected link supplied by the DOORS administrator. Verify the ZIP before extracting; the checksum line contains the hash followed by the ZIP filename.\n\n"
   +"PowerShell (replace the filename with the downloaded ZIP):\n"
   +"    Get-FileHash -Algorithm SHA256 .\\<sdk-file>.zip\n"
   +"Compare the Hash value with the first value in <sdk-file>.zip.sha256. If they differ, stop and download both files again.\n\n"
   +"2. EXTRACT AND READ\n"
   +"Extract the ZIP into a private writable folder. Open docs/index.html in a browser for the JSON contract, response examples, direct API signing guide and onboarding checklist. Install Java 21 or newer and open a terminal in the extracted folder.\n\n"
   +"3. CONFIGURE YOUR IDENTITY\n"
   +(profile.equals("UAT")?"The dedicated UAT P12 and matching client_public.pem are in doors/security. The wallet path, alias and password are already configured. The administrator must register the public certificate, permit the caller IP and enable the mapping and database service permission. Do not use the test identity in production.\n":
    "Place YOUR production P12 at ~/doors/security/doors-identity.p12. On Windows this normally means C:/Users/<username>/doors/security/doors-identity.p12. In doors-wallet.properties set DOORS_WALLET_PASS and, if needed, DOORS_WALLET_ALIAS. Blank alias selects the sole private key. No production private key or password is bundled.\n\nExport your matching public certificate (replace the placeholders):\n"
    +"    keytool -exportcert -rfc -keystore <your-p12-path>\n"
    +"        -alias <your-private-key-alias> -file client_public.pem\n"
    +"Run the two displayed keytool lines as ONE command. Send ONLY client_public.pem to the DOORS administrator. Never send your P12, private key or password. Confirm IP allowlisting, active client, enabled mapping and database service permission.\n")
   +"\n4. CHECK CONNECTION SETTINGS\n"
   +"API key, endpoint and contract are prefilled in doors-wallet.properties. Choosing UAT or Production does not change the endpoint or database. Confirm these before testing.\n"
   +(trust?"The UAT HTTPS truststore is bundled at doors/security/https-truststore.p12 and configured automatically. No extra Java parameters are needed. It contains public certificates only.\n":
     "Java default HTTPS trust is used. For private/local CA HTTPS, ask the administrator for a truststore and set DOORS_TRUSTSTORE_PATH, DOORS_TRUSTSTORE_PASS and DOORS_TRUSTSTORE_TYPE in doors-wallet.properties. Use forward slashes in Windows paths.\n")
   +"Certificate and hostname verification remain enabled. Keep doors-server-public.pem beside WriteClient.java; it is the encryption/signature public key, not a TLS truststore. Requests and responses use AES-256-GCM with RSA signatures over HTTPS.\n\n"
   +"5. RUN FROM THE EXTRACTED FOLDER\n\nRun: "+command+"\n\n"
   +"If Java is not on PATH, use its full path, for example:\n"
   +"    C:\\jdk-21\\bin\\java "+command.substring(5)+"\n\n"
   +(automatic?"Automatic versioning: DOORS assigns the record version and the SDK manages the request UUID. HTTP 201 / STORED means a new snapshot. HTTP 200 / UNCHANGED means identical data was not stored again.\n":
     "This legacy contract requires the expected version: 0 for creation, current version N for an update.\n")
   +"\n6. RETRY AND SUPPORT\n"
   +(automatic?"After an uncertain result, rerun the same command with the same JSON. The SDK retains the pending UUID automatically.\n":
     "For an uncertain result, reuse the original file, expected version and printed request UUID as the third argument.\n")
   +"Do not delete .doors-write-requests or change a pending payload until the result is resolved. Contact the DOORS administrator on VERSION_LIMIT_REACHED and provide the request ID for errors.\n\n"
   +"The ZIP contains the registered API key"+(profile.equals("UAT")?" and UAT test identity":"")+". Share only with the authorised application owner. See docs/index.html for full instructions.\n";
  return wrap(text);
 }
 private static String wrap(String text){
  var out=new StringBuilder();for(String line:text.split("\n",-1)){
   if(line.startsWith("    ")||line.startsWith("Run: ")){out.append(line).append("\r\n");continue;}
   int length=0;for(String word:line.split(" +")){if(length>0&&length+1+word.length()>78){out.append("\r\n");length=0;}if(length>0){out.append(' ');length++;}out.append(word);length+=word.length();}out.append("\r\n");
  }return out.toString();
 }
 private static boolean addUatTrust(Map<String,byte[]> files,Properties wallet,Properties settings,Path settingsPath)throws Exception {
  String configured=settings.getProperty("DOORS_TRUSTSTORE_PATH","").strip();
  if(configured.isEmpty())return false;
  if(configured.startsWith("~/"))configured=System.getProperty("user.home")+configured.substring(1);
  Path path=Path.of(configured);if(!path.isAbsolute())path=settingsPath.toAbsolutePath().getParent().resolve(path);
  if(Files.size(path)>16*1024*1024)throw new GeneralSecurityException("Truststore too large");
  var source=KeyStore.getInstance(settings.getProperty("DOORS_TRUSTSTORE_TYPE","PKCS12"));
  try(var in=Files.newInputStream(path)){source.load(in,settings.getProperty("DOORS_TRUSTSTORE_PASS","").toCharArray());}
  // Export trusted public certificates only. Never distribute source private keys or its password.
  var release=KeyStore.getInstance("PKCS12");release.load(null,"changeit".toCharArray());int count=0;
  for(var aliases=source.aliases();aliases.hasMoreElements();){String alias=aliases.nextElement();if(source.isCertificateEntry(alias)){release.setCertificateEntry("ca-"+(++count),source.getCertificate(alias));}}
  if(count==0)throw new GeneralSecurityException("No trusted certificate entries");
  var bytes=new ByteArrayOutputStream();release.store(bytes,"changeit".toCharArray());
  files.put("doors/security/https-truststore.p12",bytes.toByteArray());
  wallet.setProperty("DOORS_TRUSTSTORE_PATH","doors/security/https-truststore.p12");
  wallet.setProperty("DOORS_TRUSTSTORE_PASS","changeit");wallet.setProperty("DOORS_TRUSTSTORE_TYPE","PKCS12");return true;
 }

}
