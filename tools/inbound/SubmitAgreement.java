import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

/** JDK 21 standalone example. Never prints keys/passwords or changes the input file. */
public class SubmitAgreement {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: java SubmitAgreement.java <file.json> <instanceId> <expectedVersion> <requestUUID>");
        Path security=Path.of(System.getProperty("user.home"),"doors","security");
        Properties wallet=new Properties();try(var in=Files.newInputStream(security.resolve("doors-wallet.properties"))){wallet.load(in);}
        String base=setting(wallet,"DOORS_API_BASE_URL");
        URI endpoint=URI.create(base.replaceAll("/$","")+"/inbound/agreements");
        if(!"https".equalsIgnoreCase(endpoint.getScheme())||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null)
            throw new IllegalArgumentException("Use the approved HTTPS API base URL ending in /api/v1");
        byte[] body=Files.readAllBytes(Path.of(args[0]));
        if(body.length>1048576)throw new IllegalArgumentException("Payload exceeds 1 MiB");
        if(!args[1].matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid instance ID");
        long expected=Long.parseLong(args[2]);if(expected<0)throw new IllegalArgumentException("Invalid expected version");
        String id=UUID.fromString(args[3]).toString();long timestamp=Instant.now().getEpochSecond();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signed="DOORS-INBOUND-1\nagreements\n"+args[1]+"\n"+id+"\n1\n"+expected+"\n"+timestamp+"\n"+hash;
        char[] password=setting(wallet,"DOORS_WALLET_PASS").toCharArray();
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(security.resolve("doors-identity.p12"))){keys.load(in,password);}
        var signer=Signature.getInstance("SHA256withRSA");
        signer.initSign((PrivateKey)keys.getKey(setting(wallet,"DOORS_WALLET_ALIAS"),password));
        Arrays.fill(password,'\0');signer.update(signed.getBytes(StandardCharsets.UTF_8));
        String apiKey=System.getenv("DOORS_API_KEY");
        if(apiKey==null||apiKey.isBlank())apiKey=Files.readString(security.resolve("doors.key")).strip();
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(35))
            .header("Content-Type","application/json").header("X-API-KEY",apiKey)
            .header("Idempotency-Key",id).header("X-DOORS-Instance-ID",args[1])
            .header("X-DOORS-Schema-Version","1").header("X-DOORS-Expected-Version",Long.toString(expected))
            .header("X-DOORS-Timestamp",Long.toString(timestamp))
            .header("X-DOORS-Signature",Base64.getEncoder().encodeToString(signer.sign()))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            System.out.println("HTTP "+response.statusCode()+" | requestId="+id);System.out.println(response.body());
            if(response.statusCode()!=200&&response.statusCode()!=201)System.exit(1);
        }catch(java.io.IOException e){
            throw new IllegalStateException("Result unknown. Retain request ID "+id+" and retry with the original body and expected version.",e);
        }
    }
    static String setting(Properties p,String name){String v=System.getenv(name);if(v==null||v.isBlank())v=p.getProperty(name);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing "+name);return v;}
}
