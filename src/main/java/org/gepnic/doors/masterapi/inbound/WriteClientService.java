package org.gepnic.doors.masterapi.inbound;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.service.ApiClientService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class WriteClientService {
 private final JdbcTemplate db;private final ApiClientRepository clients;private final ApiClientService identities;private final ApplicationEventPublisher events;
 public WriteClientService(JdbcTemplate db,ApiClientRepository clients,ApiClientService identities,ApplicationEventPublisher events){this.db=db;this.clients=clients;this.identities=identities;this.events=events;}
 public record Registration(String applicationName,String environment,String description){}
 public record Security(String allowedIps,String publicKey,boolean active){}
 public record CredentialsChanged(String oldApiKey){}
 public List<Map<String,Object>> list(){return db.queryForList("SELECT p.*,c.client_name,c.is_active,c.allowed_ips,(c.client_public_key IS NOT NULL AND c.client_public_key<>'') AS has_public_key,(SELECT count(*) FROM external_client_query_map q WHERE q.client_id=c.client_id) AS read_mappings FROM write_client_profiles p JOIN external_api_clients c ON c.client_id=p.client_id ORDER BY p.created_at DESC");}
 private void validate(Registration r){if(r.applicationName()==null||r.applicationName().isBlank()||r.applicationName().length()>120||!Set.of("DEVELOPMENT","UAT","PRODUCTION").contains(r.environment()==null?"":r.environment())||r.description()!=null&&r.description().length()>2000)throw new IllegalArgumentException("Provide an application name (up to 120 characters) and environment");}
 @Transactional public long create(Registration r,String actor){validate(r);var c=identities.registerClient(r.applicationName().strip()+" - "+r.environment(),r.description(),actor);c.setIsActive(false);clients.save(c);profile(c.getClientId(),r,actor);audit(actor,"WRITE_CLIENT_CREATED",c.getClientId());return c.getClientId();}
 @Transactional public void attach(long id,Registration r,String actor){validate(r);clients.findById(id).orElseThrow(()->new IllegalArgumentException("Unknown API client"));profile(id,r,actor);audit(actor,"WRITE_CLIENT_ATTACHED",id);}
 private void profile(long id,Registration r,String actor){db.update("INSERT INTO write_client_profiles(client_id,environment,application_name,created_by) VALUES(?,?,?,?)",id,r.environment(),r.applicationName().strip(),actor);}
 public ApiClient require(long id){if(db.queryForObject("SELECT count(*) FROM write_client_profiles WHERE client_id=?",Integer.class,id)!=1)throw new IllegalArgumentException("Register this application in Write Services first");return clients.findById(id).orElseThrow(()->new IllegalArgumentException("Unknown API client"));}
 public Map<String,Object> security(long id){var c=require(id);var m=new LinkedHashMap<String,Object>();m.put("clientId",id);m.put("apiKey",c.getApiKey());m.put("allowedIps",Objects.toString(c.getAllowedIps(),""));m.put("publicKey",Objects.toString(c.getClientPublicKey(),""));m.put("active",Boolean.TRUE.equals(c.getIsActive()));return m;}
 @Transactional public void security(long id,Security s,String actor){var c=require(id);String ips=validateIps(s.allowedIps()),key=Objects.toString(s.publicKey(),"").strip();if(!key.isEmpty())validateKey(key);if(s.active()&&(ips.isEmpty()||key.isEmpty()))throw new IllegalArgumentException("An active write client requires a public key and at least one allowed IP");c.setAllowedIps(ips);c.setClientPublicKey(key);c.setIsActive(s.active());clients.save(c);events.publishEvent(new CredentialsChanged(c.getApiKey()));audit(actor,"WRITE_CLIENT_SECURITY_UPDATED",id);}
 @Transactional public String rotate(long id,String actor){var c=require(id);String old=c.getApiKey();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);c.setApiKey(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));clients.save(c);events.publishEvent(new CredentialsChanged(old));audit(actor,"WRITE_CLIENT_API_KEY_ROTATED",id);return c.getApiKey();}
 private void audit(String actor,String action,long id){db.update("INSERT INTO write_governance_events(actor,action,detail) VALUES(?,?,?)",actor,action,"clientId="+id);}
 public static String validateIps(String raw){var values=new LinkedHashSet<String>();for(String value:Objects.toString(raw,"").split("[,\\s]+")){if(value.isEmpty())continue;if(!value.matches("[0-9a-fA-F:.]+")||value.length()>45)throw new IllegalArgumentException("Use individual IPv4 or IPv6 addresses, separated by commas");try{if(!value.contains(":")){String[] parts=value.split("\\.",-1);if(parts.length!=4)throw new Exception();for(String p:parts)if(!p.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(p)>255)throw new Exception();}else if(!(java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address))throw new Exception();}catch(Exception e){throw new IllegalArgumentException("Invalid IP address: "+value);}values.add(value);}if(values.size()>100)throw new IllegalArgumentException("At most 100 IP addresses");return String.join(",",values);}
 public static void validateKey(String raw){try{if(raw.length()>20000||raw.contains("PRIVATE KEY"))throw new Exception();PublicKey key;if(raw.contains("BEGIN CERTIFICATE")){var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));cert.checkValidity();key=cert.getPublicKey();}else key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(raw.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""))));if(!(key instanceof RSAPublicKey rsa)||rsa.getModulus().bitLength()<2048)throw new Exception();}catch(Exception e){throw new IllegalArgumentException("Provide a valid RSA public key (2048 bits or greater) or an unexpired X.509 certificate; never upload a private key");}}
}
