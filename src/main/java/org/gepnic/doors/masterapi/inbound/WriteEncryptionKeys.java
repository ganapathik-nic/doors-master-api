package org.gepnic.doors.masterapi.inbound;
import org.springframework.stereotype.Service;
import org.gepnic.doors.masterapi.service.CertificateStorageService;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import java.security.*;import java.security.spec.*;import java.security.interfaces.*;import java.util.*;
@Service
public class WriteEncryptionKeys {
 private final CertificateStorageService storage;private final DoorsSigningCertificateRepository registry;
 public WriteEncryptionKeys(CertificateStorageService s,DoorsSigningCertificateRepository r){storage=s;registry=r;}
 public String activeId(){return registry.findByIsActiveTrue().orElseThrow(()->new IllegalStateException("No active DOORS encryption identity")).getKeyId();}
 public PrivateKey privateKey(String id)throws Exception{var row=registry.findByKeyId(id).orElseThrow(()->new GeneralSecurityException("Unknown key"));if(row.getExpiryAt()!=null&&row.getExpiryAt().isBefore(java.time.LocalDateTime.now()))throw new GeneralSecurityException("Expired key");return storage.getSigningPrivateKey(id);}
 public PublicKey publicKey(String id)throws Exception{var key=(RSAPrivateCrtKey)privateKey(id);return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(),key.getPublicExponent()));}
 public Map<String,String> active()throws Exception{String id=activeId();return Map.of("kid",id,"publicKey",WriteCrypto.pem(publicKey(id)),"encryption",WriteCrypto.MODE);}
}
