import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.net.*;
import java.net.http.*;
import java.util.*;

/** Standalone Java 21 signed-write client; no private material is generated or uploaded. */
public class WriteClient {
 public static void main(String[] args)throws Exception {
  try{Files.createDirectories(Path.of(".doors-write-requests"));try(var channel=java.nio.channels.FileChannel.open(Path.of(".doors-write-requests/sdk.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){if(lock==null)throw new IllegalArgumentException("Another SDK submission is running in this folder. Wait and retry.");run(args);}}catch(IllegalArgumentException e){System.err.println("DOORS: "+e.getMessage());System.exit(2);}
 }
 static String requestId(String value){
  if(value==null)return UUID.randomUUID().toString();
  if(!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid request UUID. Omit the third argument to generate one, or use PowerShell: [guid]::NewGuid().ToString(). For a retry, reuse the original UUID; this is not the API key.");
  return UUID.fromString(value).toString();
 }
 static void run(String[] args)throws Exception {
  if(args.length<1||args.length>3)throw new IllegalArgumentException("Usage: java WriteClient.java payload.json [expectedRecordVersion [requestUUID]]");
  String requestId=requestId(args.length==3?args[2]:null);
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of("doors-wallet.properties"))){p.load(in);}
  configureTruststore(p);
  String service=setting(p,"DOORS_SERVICE"),instance=setting(p,"DOORS_INSTANCE");
  if(!service.matches("[a-z][a-z-]{0,39}")||!instance.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid service or instance");
  String base=setting(p,"DOORS_API_BASE_URL").replaceAll("/$","");URI uri=URI.create(base+"/inbound/"+service);
  if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!base.endsWith("/api/v1"))throw new IllegalArgumentException("Use an approved HTTPS API base ending in /api/v1");
  int protocol=Integer.parseInt(setting(p,"DOORS_PROTOCOL_VERSION")),contract=Integer.parseInt(setting(p,"DOORS_CONTRACT_VERSION"));
  if(protocol!=1||contract<1)throw new IllegalArgumentException("Unsupported protocol or contract version");
  byte[] body=Files.readAllBytes(Path.of(args[0]));if(body.length>1048576)throw new IllegalArgumentException("Payload exceeds 1 MiB");
  boolean automatic=args.length==1;
  if(automatic&&!Boolean.parseBoolean(optional(p,"DOORS_AUTOMATIC_VERSIONING")))throw new IllegalArgumentException("This contract requires the legacy command: java WriteClient.java payload.json expectedRecordVersion [requestUUID]. Approve/install the new contract to use automatic versioning.");
  long expected=automatic?-1:Long.parseLong(args[1]);if(expected< -1||expected==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid expected version");
  long timestamp=Instant.now().getEpochSecond();
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  Path pending=null;
  if(automatic){
   String scope=uri+"\n"+instance+"\n"+contract+"\n"+setting(p,"DOORS_API_KEY");
   pending=Path.of(".doors-write-requests","pending-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)))+".properties");
   if(Files.exists(pending)){Properties saved=new Properties();try(var in=Files.newInputStream(pending)){saved.load(in);}if(!hash.equals(saved.getProperty("payloadSha256")))throw new IllegalArgumentException("A previous submission has an unresolved result. Retry its original JSON file first; do not delete retry metadata.");requestId=requestId(saved.getProperty("requestId"));}
   else{Properties saved=new Properties();saved.setProperty("requestId",requestId);saved.setProperty("payloadSha256",hash);try(var out=Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW)){saved.store(out,"Pending DOORS request - retain for retries");}}
  }
  Properties receipt=new Properties();receipt.setProperty("requestId",requestId);receipt.setProperty("expectedRecordVersion",String.valueOf(expected));receipt.setProperty("payloadSha256",hash);receipt.setProperty("endpoint",uri.toString());receipt.setProperty("instance",instance);
  Path folder=Path.of(".doors-write-requests"),record=folder.resolve(requestId+".properties");Files.createDirectories(folder);
  if(Files.exists(record)){Properties previous=new Properties();try(var in=Files.newInputStream(record)){previous.load(in);}if(!previous.equals(receipt))throw new IllegalArgumentException("This request UUID was already saved with different data, destination or expected version. Reuse its original inputs for a retry, or choose a new UUID for a new submission.");}
  else try(var out=Files.newOutputStream(record,StandardOpenOption.CREATE_NEW)){receipt.store(out,"DOORS retry metadata - no credentials or payload");}
  System.out.println("Request ID: "+requestId+"; "+(expected == -1 ? "Versioning: automatic" : "expected record version: "+expected)+"; retry metadata: "+record.toAbsolutePath());
  String signing="DOORS-INBOUND-1\n"+service+"\n"+instance+"\n"+requestId+"\n1\n"+expected+"\n"+timestamp+"\n"+hash;
  char[] password=setting(p,"DOORS_WALLET_PASS").toCharArray();KeyStore store=KeyStore.getInstance("PKCS12");
  try(var in=Files.newInputStream(localPath(setting(p,"DOORS_WALLET_PATH")))){store.load(in,password);}
  PrivateKey clientKey=signingKey(store,optional(p,"DOORS_WALLET_ALIAS"),password);Arrays.fill(password,'\0');
  String kid=setting(p,"DOORS_SERVER_KEY_ID");PublicKey serverKey=WriteCrypto.publicKey(Files.readString(localPath(setting(p,"DOORS_SERVER_PUBLIC_KEY_PATH"))));
  String envelope=WriteCrypto.seal(body,serverKey,kid);body=envelope.getBytes(StandardCharsets.UTF_8);
  signing=WriteCrypto.requestText(service,instance,requestId,1,expected,timestamp,contract,envelope);
  String signed=WriteCrypto.sign(signing,clientKey);
  var request=HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(40)).header("Content-Type","application/json")
   .header("X-DOORS-Encryption",WriteCrypto.MODE).header("X-API-KEY",setting(p,"DOORS_API_KEY")).header("X-DOORS-Instance-ID",instance)
   .header("X-DOORS-Protocol-Version",String.valueOf(protocol)).header("X-DOORS-Contract-Version",String.valueOf(contract))
   .header("X-DOORS-Schema-Version","1").header("X-DOORS-Expected-Version",String.valueOf(expected))
   .header("Idempotency-Key",requestId).header("X-DOORS-Timestamp",String.valueOf(timestamp))
   .header("X-DOORS-Signature",signed).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

  try {var response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(5)).build().send(request,HttpResponse.BodyHandlers.ofString());System.out.println("HTTP "+response.statusCode());String result=response.body();
   if(WriteCrypto.MODE.equals(response.headers().firstValue("X-DOORS-Encryption").orElse(""))){if(!kid.equals(WriteCrypto.envelope(result).get("kid")))throw new SecurityException("Unexpected server key ID; result unknown");WriteCrypto.verify(WriteCrypto.responseText(requestId,response.statusCode(),result),response.headers().firstValue("X-DOORS-Response-Signature").orElseThrow(),serverKey);result=new String(WriteCrypto.open(result,clientKey),StandardCharsets.UTF_8);}
   else if(response.statusCode()==200||response.statusCode()==201)throw new SecurityException("Expected encrypted response; retain request ID and retry metadata");
   System.out.println(result);if(pending!=null&&(response.statusCode()==200||response.statusCode()==201||Set.of(400,401,403,404,409,413,422,429).contains(response.statusCode()))&&!result.contains("WRITE_RESULT_UNKNOWN"))Files.deleteIfExists(pending);if(response.statusCode()!=200&&response.statusCode()!=201)System.exit(1);}
  catch(java.io.IOException e){throw new IllegalStateException("Result unknown. For automatic mode, rerun the same command with the original JSON file. For legacy mode, reuse the SAME request ID, body and expected version.",e);}
 }
 static Path localPath(String value){
  if(value.startsWith("~/"))return Path.of(System.getProperty("user.home"),value.substring(2));
  return Path.of(value);
 }
 static PrivateKey signingKey(KeyStore store,String alias,char[] password)throws Exception {
  if(alias.isBlank()){
   var keys=new ArrayList<String>();var names=store.aliases();
   while(names.hasMoreElements()){String name=names.nextElement();if(store.isKeyEntry(name))keys.add(name);}
   if(keys.size()!=1)throw new IllegalArgumentException("Wallet must contain exactly one private-key entry when DOORS_WALLET_ALIAS is blank. Set the correct alias explicitly.");
   alias=keys.getFirst();
  }
  if(!store.isKeyEntry(alias))throw new IllegalArgumentException("DOORS_WALLET_ALIAS '"+alias+"' is absent or is not a private-key entry. Check the alias with keytool -list -keystore <wallet.p12>.");
  Key key=store.getKey(alias,password);
  if(!(key instanceof PrivateKey)||!"RSA".equalsIgnoreCase(key.getAlgorithm()))throw new IllegalArgumentException("The selected wallet entry must contain an RSA private key.");
  return (PrivateKey)key;
 }
 static void configureTruststore(Properties p){
  String path=optional(p,"DOORS_TRUSTSTORE_PATH");
  if(path.isBlank())return; // Preserve JVM defaults and existing -D options.
  if(!Files.isRegularFile(localPath(path)))throw new IllegalArgumentException("Truststore not found: "+path+". Set DOORS_TRUSTSTORE_PATH to an existing file.");
  System.setProperty("javax.net.ssl.trustStore",localPath(path).toAbsolutePath().toString());
  System.setProperty("javax.net.ssl.trustStorePassword",optional(p,"DOORS_TRUSTSTORE_PASS"));
  String type=optional(p,"DOORS_TRUSTSTORE_TYPE");
  System.setProperty("javax.net.ssl.trustStoreType",type.isBlank()?"PKCS12":type);
 }
 static String optional(Properties p,String key){String value=System.getenv(key);return value==null||value.isBlank()?p.getProperty(key,""):value;}
 static String setting(Properties p,String key){String value=System.getenv(key);if(value==null||value.isBlank())value=p.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Set "+key+" in the environment or doors-wallet.properties");return value;}
}
