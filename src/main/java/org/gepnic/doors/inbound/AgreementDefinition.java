package org.gepnic.doors.inbound;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class AgreementDefinition {
 private AgreementDefinition(){}
 public static String ddl(){try(var in=AgreementDefinition.class.getResourceAsStream("/inbound/agreement-provision.sql")){
  if(in==null)throw new IllegalStateException("Missing agreement definition");
  return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
 }catch(java.io.IOException e){throw new IllegalStateException(e);}}
 public static String hash(){return InboundProtocol.sha(ddl().getBytes(StandardCharsets.UTF_8));}
 public static Map<String,Object> describe(){
  var m=new LinkedHashMap<String,Object>();
  m.put("service","agreements");m.put("schemaVersion",1);
  m.put("businessKey",List.of("tender_id","agreement_no"));m.put("table","doors_inbound.agreement_version");
  m.put("extractedColumns",List.of("agreement_date","vendor_id: party_1.vendor_id","department_user_name: party_2.user_name","tender_nit_ref_no","loa_ref_no"));
  m.put("payloadType","json");m.put("versioning","Immutable full snapshots; optimistic expected version");
  m.put("required",List.of("tender_id","agreement_no","agreement_date"));m.put("definitionHash",hash());
  m.put("writeSql","SELECT doors_inbound.store_agreement(?,?,?,?,?,?,?::json,?)");m.put("ddl",ddl());return m;
 }
 public record Control(String operation,String instance,String sample,long timestamp,String definitionHash){}
}
