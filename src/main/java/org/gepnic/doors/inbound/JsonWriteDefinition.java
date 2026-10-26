package org.gepnic.doors.inbound;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Deterministic JSON discovery and closed-template SQL generation. Never accepts SQL from a caller. */
public final class JsonWriteDefinition {
 private JsonWriteDefinition(){}
 public static final String REJECT_UNAPPROVED="REJECT_UNAPPROVED",ALLOW_PAYLOAD_ONLY="ALLOW_PAYLOAD_ONLY";
 private static final List<String> BASE_TYPES=List.of("text","varchar","character","smallint","integer","bigint","numeric","real","double precision","money","boolean","date","time","time with time zone","timestamp","timestamp with time zone","interval","uuid","json","jsonb","jsonpath","bytea","bit","bit varying","inet","cidr","macaddr","macaddr8","point","line","lseg","box","path","polygon","circle","tsvector","tsquery","xml","int4range","int8range","numrange","tsrange","tstzrange","daterange","int4multirange","int8multirange","nummultirange","tsmultirange","tstzmultirange","datemultirange","oid","pg_lsn","pg_snapshot","txid_snapshot");
 public static List<String> types(){var result=new ArrayList<>(BASE_TYPES);BASE_TYPES.forEach(t->result.add(t+"[]"));return result;}
 public static int varcharLength(String type){
  if(type==null||!type.matches("varchar\\([1-9][0-9]{0,7}\\)"))return 0;
  int n=Integer.parseInt(type.substring(8,type.length()-1));return n<=10485760?n:0;
 }
 public static boolean supported(String type){return type!=null&&(varcharLength(type)>0||BASE_TYPES.contains(type)||type.endsWith("[]")&&BASE_TYPES.contains(type.substring(0,type.length()-2)));}
 public static boolean keyType(String type){return varcharLength(type)>0||Set.of("text","varchar","numeric","smallint","integer","bigint","uuid","date").contains(type);}
 public static boolean extended(Spec s){return s.fields().stream().anyMatch(f->!Set.of("text","numeric","boolean").contains(f.type()));}
 public record Field(String path,String column,String type){}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Spec(String service,List<String> businessKey,List<Field> fields,Integer storageVersion,String additionalFieldPolicy,List<String> allowedPaths){
  public Spec(String service,List<String> businessKey,List<Field> fields){this(service,businessKey,fields,null,null,null);}
  public Spec(String service,List<String> businessKey,List<Field> fields,Integer storageVersion){this(service,businessKey,fields,storageVersion,null,null);}
 }
 public static String additionalFieldPolicy(Spec s){return REJECT_UNAPPROVED.equals(s.additionalFieldPolicy())?REJECT_UNAPPROVED:ALLOW_PAYLOAD_ONLY;}
 public static boolean shared(Spec s){return Integer.valueOf(2).equals(s.storageVersion())||automatic(s);}
 public static boolean automatic(Spec s){return Integer.valueOf(3).equals(s.storageVersion());}
 public static String signatureTypes(Spec s){return "text,bigint,uuid,bigint,text,json,text"+(automatic(s)?",bigint":"");}
 public static String object(Spec s,String name){return shared(s)?(name.equals("store_document")?"store_"+table(s):table(s)+"_"+name):name;}

 public record DatabaseTarget(String host,int port,String database,String username,String encryptedPassword){}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Control(String operation,String instance,String sample,long timestamp,Spec spec,DatabaseTarget target){
  public Control(String operation,String instance,String sample,long timestamp,Spec spec){this(operation,instance,sample,timestamp,spec,null);}
 }
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Transit(InboundProtocol.Submission submission,Spec spec,DatabaseTarget target,Long maxVersions){
  public Transit(InboundProtocol.Submission submission,Spec spec,DatabaseTarget target){this(submission,spec,target,null);}
  public Transit(InboundProtocol.Submission submission,Spec spec){this(submission,spec,null,null);}
 }
 public static String json(Object o){try{return InboundProtocol.JSON.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Invalid definition");}}
 public static JsonNode parse(String sample){try{
  if(sample==null||sample.getBytes(StandardCharsets.UTF_8).length>InboundProtocol.MAX_BODY)throw new IllegalArgumentException("Maximum sample size is 1 MiB");
  var n=InboundProtocol.JSON.readTree(sample);if(n==null||!n.isObject())throw new IllegalArgumentException("Upload one JSON object. Submit individual records rather than a root array.");return n;
 }catch(java.io.IOException e){throw new IllegalArgumentException("Invalid JSON: check syntax, duplicate properties and trailing commas");}}
 public static List<Field> fields(String sample){var out=new ArrayList<Field>();walk(parse(sample),"",out);if(out.isEmpty())throw new IllegalArgumentException("No scalar fields found");return out;}
 private static void walk(JsonNode n,String path,List<Field> out){
  var names=new ArrayList<String>();n.fieldNames().forEachRemaining(names::add);Collections.sort(names);
  for(String name:names){String p=path+"/"+name.replace("~","~0").replace("/","~1");var v=n.get(name);
   if(p.length()>400)throw new IllegalArgumentException("Field path is too long");
   {
    if(out.size()>=100)throw new IllegalArgumentException("At most 100 scalar fields can be extracted");
    String column=columnName(p,out);
    out.add(new Field(p,column,v.isContainerNode()?"json":v.isNull()?"text":v.isBoolean()?"boolean":v.isNumber()?"numeric":"text"));
    if(v.isObject())walk(v,p,out);
   }
  }
 }
 // Generate names only during discovery; persisted specs retain their original columns and hashes.
 private static String columnName(String path,List<Field> fields){
  String base=Arrays.stream(path.substring(1).split("/",-1))
   .map(part->part.replace("~1","/").replace("~0","~").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]","_"))
   .collect(java.util.stream.Collectors.joining("_"));
  if(base.isEmpty()||!base.matches("[a-z].*"))base="field_"+base;
  var used=new HashSet<String>(Set.of("instance_id","business_key","record_version","payload","client_id","request_id","received_at"));
  fields.forEach(f->used.add(f.column()));
  if(base.length()<=40&&!used.contains(base))return base;
  String hash=InboundProtocol.sha(path.getBytes(StandardCharsets.UTF_8)).substring(0,12);
  String prefix=base.substring(0,Math.min(base.length(),27));
  String candidate=prefix+"_"+hash;
  for(int i=2;used.contains(candidate);i++){
   String suffix="_"+hash+"_"+i;
   candidate=base.substring(0,Math.min(base.length(),40-suffix.length()))+suffix;
  }
  return candidate;
 }
 public static List<String> suggestedKeys(List<Field> fields){return fields.stream().filter(f->f.type().equals("text")||f.type().equals("numeric")).filter(f->f.path().matches("(?i).*(/id|_id|_no|_code|/code|/reference)$")).limit(4).map(Field::path).toList();}
 public static Spec create(String service,List<String> keys,String sample){var detected=fields(sample);var s=new Spec(service,keys,detected,3,REJECT_UNAPPROVED,detected.stream().map(Field::path).toList());check(s);validate(s,sample);return s;}
 public static Spec create(String service,List<String> keys,String sample,Map<String,String> overrides){
  var detected=fields(sample);
  if(overrides==null||overrides.isEmpty())return create(service,keys,sample);
  var paths=new HashSet<String>();detected.forEach(f->paths.add(f.path()));
  if(!paths.containsAll(overrides.keySet())||overrides.values().stream().anyMatch(t->t==null||!supported(t)))throw new IllegalArgumentException("Invalid field type selection");
  var spec=new Spec(service,keys,detected.stream().map(f->new Field(f.path(),f.column(),overrides.getOrDefault(f.path(),f.type()))).toList(),3,REJECT_UNAPPROVED,detected.stream().map(Field::path).toList());
  check(spec);validate(spec,sample);return spec;
 }
 public static Spec create(String service,List<String> keys,String sample,Map<String,String> types,Map<String,String> columns){
  var original=create(service,keys,sample,types);
  if(columns==null||columns.isEmpty())return original;
  var paths=new HashSet<String>();original.fields().forEach(f->paths.add(f.path()));
  if(!paths.containsAll(columns.keySet()))throw new IllegalArgumentException("Unknown JSON field in column names");
  var used=new HashSet<String>();
  for(var f:original.fields()){
   String c=columns.getOrDefault(f.path(),f.column());
   if(c==null||!c.matches("[a-z][a-z0-9_]{0,39}"))throw new IllegalArgumentException("Column names must start with a lowercase letter and use up to 40 lowercase letters, digits or underscores");
   if(Set.of("instance_id","business_key","record_version","payload","client_id","request_id","received_at").contains(c))throw new IllegalArgumentException("Reserved column name: "+c);
   if(!used.add(c))throw new IllegalArgumentException("Duplicate column name: "+c);
  }
  var spec=new Spec(service,keys,original.fields().stream().map(f->new Field(f.path(),columns.getOrDefault(f.path(),f.column()),f.type())).toList(),original.storageVersion(),original.additionalFieldPolicy(),original.allowedPaths());
  check(spec);validate(spec,sample);return spec;
 }
 // New proposals extract scalar leaves only. Stored legacy specs remain executable unchanged.
 public static List<Field> extractableFields(String sample){var root=parse(sample);return fields(sample).stream().filter(f->!root.at(f.path()).isContainerNode()).toList();}
 public static Spec createSelected(String service,List<String> keys,String sample,Map<String,String> types,Map<String,String> columns,List<String> selected){
  return createSelected(service,keys,sample,types,columns,selected,REJECT_UNAPPROVED);
 }
 public static Spec createSelected(String service,List<String> keys,String sample,Map<String,String> types,Map<String,String> columns,List<String> selected,String policy){
  var available=extractableFields(sample);var paths=new HashSet<String>();available.forEach(f->paths.add(f.path()));
  if(selected!=null&&(selected.isEmpty()||new HashSet<>(selected).size()!=selected.size()||!paths.containsAll(selected)))throw new IllegalArgumentException("Select valid scalar fields to extract");
  var retained=selected==null?paths:new HashSet<>(selected);
  if(keys==null||!retained.containsAll(keys))throw new IllegalArgumentException("Business-key fields must be retained as columns");
  if(types!=null&&!retained.containsAll(types.keySet())||columns!=null&&!retained.containsAll(columns.keySet()))throw new IllegalArgumentException("Column settings must reference retained fields only");
  var fields=available.stream().filter(f->retained.contains(f.path())).map(f->new Field(f.path(),columns==null?f.column():columns.getOrDefault(f.path(),f.column()),types==null?f.type():types.getOrDefault(f.path(),f.type()))).toList();
  if(fields.stream().anyMatch(f->f.type()!=null&&Set.of("json","jsonb","json[]","jsonb[]").contains(f.type())))throw new IllegalArgumentException("The payload column stores the complete JSON; extracted columns must use non-JSON types");
  if(!Set.of(REJECT_UNAPPROVED,ALLOW_PAYLOAD_ONLY).contains(policy))throw new IllegalArgumentException("Choose a valid additional-field policy");
  var allowed=fields(sample).stream().map(Field::path).distinct().sorted().toList();
  var spec=new Spec(service,keys,fields,3,policy,allowed);check(spec);validate(spec,sample);return spec;
 }
 public static void check(Spec s){
  if(s==null||s.service()==null||!s.service().matches("[a-z][a-z-]{2,29}")||Set.of("agreements","dynamic","governance").contains(s.service()))throw new IllegalArgumentException("Choose a service code of 3-30 lowercase letters/hyphens (agreements is reserved for legacy contracts)");
  if(s.fields()==null||s.fields().isEmpty()||s.fields().size()>100||s.businessKey()==null||s.businessKey().isEmpty()||s.businessKey().size()>6||new HashSet<>(s.businessKey()).size()!=s.businessKey().size())throw new IllegalArgumentException("Select 1-6 different business-key fields");
  if(s.storageVersion()!=null&&!shared(s))throw new IllegalArgumentException("Unsupported storage definition version");
  if(shared(s)&&(s.service().matches(".*-(head|receipt|latest|write-grant)$")||Set.of("head","receipt","latest","write-grant","store-document").contains(s.service())))throw new IllegalArgumentException("Service name uses a reserved storage suffix");
  var paths=new HashSet<String>();var columns=new HashSet<String>();
  for(var f:s.fields())if(f==null||f.path()==null||!f.path().startsWith("/")||f.path().length()>400||f.path().matches(".*~(?![01]).*")||f.column()==null||!f.column().matches("[a-z][a-z0-9_]{0,39}")||!supported(f.type())||!paths.add(f.path())||!columns.add(f.column())||Set.of("instance_id","business_key","record_version","payload","client_id","request_id","received_at").contains(f.column()))throw new IllegalArgumentException("Invalid field definition");
  for(String k:s.businessKey())if(s.fields().stream().noneMatch(f->f.path().equals(k)&&keyType(f.type())))throw new IllegalArgumentException("Keys must be scalar text or number fields");
 }
 public static JsonNode validate(Spec s,String sample){check(s);var n=parse(sample);
  if(REJECT_UNAPPROVED.equals(additionalFieldPolicy(s))){
   var allowed=new HashSet<>(s.allowedPaths()==null?List.<String>of():s.allowedPaths());var supplied=new ArrayList<String>();collectPaths(n,"",supplied);
   var unknown=supplied.stream().filter(p->!allowed.contains(p)).sorted().toList();
   if(!unknown.isEmpty())throw new UnknownJsonFieldsException(unknown);
  }
  for(var f:s.fields()){var v=n.at(f.path());if(s.businessKey().contains(f.path())&&(v.isMissingNode()||v.isNull()||(!v.isTextual()&&!v.isIntegralNumber())||!validKeyType(f.type(),v)||v.asText().isBlank()||v.asText().length()>200||!v.asText().equals(v.asText().strip())))throw new IllegalArgumentException("Missing or invalid business key: "+f.path());
   if(!v.isMissingNode()&&!v.isNull()&&!compatible(f.type(),v))throw new IllegalArgumentException("Expected "+f.type()+" at "+f.path());
  }return n;
 }
 private static void collectPaths(JsonNode node,String parent,List<String> out){
  if(!node.isObject())return;node.fields().forEachRemaining(entry->{String path=parent+"/"+entry.getKey().replace("~","~0").replace("/","~1");out.add(path);if(entry.getValue().isObject())collectPaths(entry.getValue(),path,out);});
 }
 public static final class UnknownJsonFieldsException extends IllegalArgumentException{
  private final List<String> paths;public UnknownJsonFieldsException(List<String> paths){super("Unapproved JSON fields: "+String.join(", ",paths));this.paths=List.copyOf(paths);}public List<String> paths(){return paths;}
 }
 private static boolean validKeyType(String type,JsonNode value){
  if(!Set.of("numeric","smallint","integer","bigint").contains(type))return true;
  try{return new java.math.BigDecimal(value.asText()).toBigIntegerExact().toString().equals(value.asText());}catch(Exception e){return false;}
 }
 private static boolean compatible(String type,JsonNode value){
  int length=varcharLength(type);if(length>0)return value.isValueNode()&&value.asText().codePointCount(0,value.asText().length())<=length;
  if(type.equals("json")||type.equals("jsonb"))return true;
  if(type.endsWith("[]")){if(!value.isArray())return false;for(var item:value)if(!item.isNull()&&!compatible(type.substring(0,type.length()-2),item))return false;return true;}
  if(!Set.of("text","numeric","boolean").contains(type)){
   if(!value.isValueNode())return false;
   try{switch(type){case "date" -> java.time.LocalDate.parse(value.asText());case "time" -> java.time.LocalTime.parse(value.asText());case "time with time zone" -> java.time.OffsetTime.parse(value.asText());case "timestamp" -> java.time.LocalDateTime.parse(value.asText().replace(' ','T'));case "timestamp with time zone" -> java.time.OffsetDateTime.parse(value.asText().replace(' ','T'));default -> {}}}catch(Exception e){return false;}
   return true; // PostgreSQL performs native conversion in preview and sandbox.
  }
  if(!value.isValueNode())return false;
  if(type.equals("text"))return true;
  if(type.equals("boolean"))return value.isBoolean()||(value.isTextual()&&Set.of("true","false").contains(value.asText()));
  if(value.isNumber())return true;
  if(!value.isTextual()||!value.asText().matches("[+-]?[0-9]+(?:\\.[0-9]+)?")||value.asText().length()>100)return false;
  try{new java.math.BigDecimal(value.asText());return true;}catch(NumberFormatException e){return false;}
 }
 public static String hash(Spec s){check(s);return InboundProtocol.sha(("DOORS-JSON-WRITE-1\n"+json(s)).getBytes(StandardCharsets.UTF_8));}
 public static String schema(Spec s){return shared(s)?"doors_write":"doors_write_"+hash(s).substring(0,16);}
 public static String table(Spec s){return s.service().replace('-','_')+(shared(s)?"":"_version");}
 public static Spec from(Object value){try{var n=InboundProtocol.JSON.readTree(value.toString());return InboundProtocol.JSON.treeToValue(n.get("spec"),Spec.class);}catch(Exception e){throw new IllegalStateException("Invalid stored JSON write definition");}}
 public static boolean dynamic(Object value){try{return InboundProtocol.JSON.readTree(value.toString()).has("spec");}catch(Exception e){return false;}}
 public static Map<String,Object> describe(Spec s){check(s);var m=new LinkedHashMap<String,Object>();m.put("kind","json-write-v1");m.put("spec",s);m.put("service",s.service());m.put("schemaVersion",1);m.put("businessKey",s.businessKey());m.put("fields",s.fields());m.put("table",schema(s)+"."+table(s));m.put("required",s.businessKey());m.put("additionalFieldPolicy",additionalFieldPolicy(s));m.put("extractedColumns",s.fields().stream().map(f->f.column()+": "+f.path()+" ("+f.type()+")").toList());m.put("definitionHash",hash(s));m.put("payloadType","json");m.put("versioning",automatic(s)?"Automatic snapshots; identical JSON skipped; client version limit":"Immutable full snapshots; optimistic expected version");m.put("writeSql","SELECT "+schema(s)+"."+object(s,"store_document")+"(?,?,?::uuid,?,?,?::json,?)");m.put("ddl",dbaDdl(s));return m;}
 private static String literal(String s){return "E'"+s.replace("\\","\\\\").replace("'","''")+"'";}
 private static String path(String pointer){return "ARRAY["+String.join(",",Arrays.stream(pointer.substring(1).split("/",-1)).map(p->literal(p.replace("~1","/").replace("~0","~"))).toList())+"]::text[]";}
 public static Map<String,Object> mappedSample(Spec s,String sample){var n=validate(s,sample);var values=new LinkedHashMap<String,Object>();for(var f:s.fields())values.put(f.column(),n.at(f.path()).isMissingNode()?null:n.at(f.path()));return values;}
 public static String recordColumns(Spec s){check(s);return String.join(",",s.fields().stream().map(f->"\""+f.column()+"\" "+f.type()).toList());}
 private static String mappedSql(Spec s){
  var groups=new ArrayList<String>();for(int start=0;start<s.fields().size();start+=40){var args=new ArrayList<String>();for(var f:s.fields().subList(start,Math.min(start+40,s.fields().size()))){args.add(literal(f.column()));args.add("p_payload #> "+path(f.path()));}groups.add("jsonb_build_object("+String.join(",",args)+")");}return String.join(" || ",groups);
 }
 public static String ddl(Spec s){
  String sql=renderDdl(s);if(automatic(s))sql=automaticDdl(s,sql);if(!shared(s))return sql;
  String ns=schema(s);
  sql=sql.replace("CREATE SCHEMA "+ns+";","CREATE SCHEMA IF NOT EXISTS "+ns+";");
  sql=sql.replace("REVOKE ALL ON ALL TABLES IN SCHEMA "+ns+" FROM PUBLIC;","REVOKE ALL ON TABLE "+ns+"."+table(s)+","+ns+"."+object(s,"head")+","+ns+"."+object(s,"receipt")+","+ns+"."+object(s,"service_grant")+","+ns+"."+object(s,"latest")+" FROM PUBLIC;");
  return sql.replace("EXECUTE on store_document", "EXECUTE on "+object(s,"store_document")).replace("Provision service_grant", "Provision "+object(s,"service_grant"));
 }
 public static String automaticDdl(Spec s,String sql){
  String ns=schema(s),table=ns+"."+table(s);
  String projection="v.instance_id,"+s.fields().stream().filter(f->s.businessKey().contains(f.path())).map(f->"v.\""+f.column()+"\"").collect(java.util.stream.Collectors.joining(","))+",v.payload";
  sql=sql.replace(" AS SELECT v.* FROM "," AS SELECT "+projection+" FROM ")
   .replace("p_definition text) RETURNS", "p_definition text,p_max_versions bigint DEFAULT NULL) RETURNS")
   .replace("(text,bigint,uuid,bigint,text,json,text)","(text,bigint,uuid,bigint,text,json,text,bigint)")
   .replace("p_expected<0", "p_expected< -1 OR (p_max_versions IS NOT NULL AND p_max_versions<1)");
  String gate="IF old<>p_expected THEN RAISE EXCEPTION 'Version conflict' USING ERRCODE='DW001'; END IF;";
  String unchanged="IF old>0 AND EXISTS(SELECT 1 FROM "+table+" WHERE instance_id=p_instance AND business_key=k AND record_version=old AND payload::jsonb=p_payload::jsonb) THEN\n"
   +"answer:=jsonb_build_object('outcome','UNCHANGED','message','Already up to date. No new version created.','instanceId',p_instance,'requestId',p_request,'recordVersion',old,'replayed',false);\n"
   +"INSERT INTO "+ns+"."+object(s,"receipt")+" VALUES(p_instance,p_client,p_request,p_fingerprint,answer); RETURN answer; END IF;\n"
   +"IF p_expected>=0 AND old<>p_expected THEN RAISE EXCEPTION 'Version conflict' USING ERRCODE='DW001'; END IF;\n"
   +"IF p_max_versions IS NOT NULL AND old>=p_max_versions THEN RAISE EXCEPTION 'Version limit reached for this record. Contact the DOORS Administrator.' USING ERRCODE='DW004'; END IF;";
  return sql.replace(gate,unchanged);
 }
 public static String dbaNotes(Spec s){
  String ns=schema(s);
  StringBuilder n=new StringBuilder("-- DOORS WRITE SERVICE - DBA INSTALLATION NOTES\n");
  n.append("-- Service: ").append(s.service()).append("\n-- Definition hash: ").append(hash(s)).append("\n");
  n.append("-- Match this definition to the approved Write Library contract before installation.\n");
  n.append("-- Record identity: GePNIC instance + ").append(String.join(" + ",s.businessKey())).append("\n");
  n.append("-- Creates these objects in schema ").append(ns).append(":\n");
  n.append("-- ").append(table(s)).append(": complete JSON payload, extracted columns and version history.\n");
  n.append("-- ").append(object(s,"head")).append(": tracks the latest record version.\n");
  n.append("-- ").append(object(s,"receipt")).append(": saved request results for safe retries.\n");
  n.append("-- ").append(object(s,"service_grant")).append(": approved client/instance write permissions.\n");
  n.append("-- ").append(object(s,"latest")).append(": view returning only the latest snapshot per record.\n");
  n.append("-- ").append(object(s,"store_document")).append(": controlled SECURITY DEFINER write function.\n");
  n.append("-- The API calls this function on pre-created objects; it does not create tables.\n");
  if(automatic(s))n.append("-- Automatic mode uses expected version -1. Identical latest JSON returns UNCHANGED.\n-- Changed JSON appends a snapshot under a record lock; the Master supplies the client limit.\n-- The latest view exposes routing/record keys and payload only.\n");
  n.append("-- Expected version 0 creates version 1; later updates supply the current version\n-- and append the next snapshot. Earlier snapshots remain; stale versions fail.\n");
  n.append("-- A repeated request UUID with the same fingerprint returns its saved result;\n-- a different fingerprint is rejected. Locks coordinate concurrent submissions.\n");
  n.append("-- doors is created only if absent. Existing passwords/settings are preserved.\n-- A new doors login requires a separately configured password (psql: \\password doors).\n");
  n.append("-- doors receives CONNECT, schema USAGE, function EXECUTE and SELECT on the latest\n-- view. Existing gepuser receives schema USAGE and SELECT on history/latest.\n");
  n.append("-- No direct table writes or schema CREATE are granted to doors; the final check\n-- rejects such existing/inherited rights. Do not make doors the object owner.\n");
  n.append("-- Run as the authorised DBA/schema owner in the intended GePNIC database.\n-- This transactional creation script is not a migration or a rerunnable installer.\n-- If these service objects already exist, obtain a reviewed migration instead.\n");
  n.append("-- Existing public-schema tables/views receive SELECT grants for doors; their data\n-- and structure are unchanged. Future objects need separate default privileges.\n-- PUBLIC access to doors_write is revoked: review existing shared use.\n");
  n.append("-- Installation alone does not enable client writes. Provision the approved\n-- service/instance/client grant separately, then verify the mapping in DOORS.\n-- Record version, JSON contract version and API protocol version are independent.\n\n");
  return n.toString();
 }
 public static String dbaDdl(Spec s){
  String ns=schema(s),table=ns+"."+table(s),latest=ns+"."+object(s,"latest");
  String grants="-- doors is created if absent; gepuser must already exist.\n"
   +"GRANT USAGE ON SCHEMA public TO doors;\n"
   +"GRANT SELECT ON ALL TABLES IN SCHEMA public TO doors;\n"
   +"GRANT USAGE ON SCHEMA "+ns+" TO doors, gepuser;\n"
   +"GRANT EXECUTE ON FUNCTION "+ns+"."+object(s,"store_document")+"("+signatureTypes(s)+") TO doors;\n"
   +"GRANT SELECT ON "+latest+" TO doors;\n"
   +"GRANT SELECT ON "+table+", "+latest+" TO gepuser;\n"
   +"DO $permissions$ BEGIN IF has_schema_privilege('doors','"+ns+"','CREATE') OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+ns+"' AND c.relkind IN ('r','p','v','m','f') AND has_table_privilege('doors',c.oid,'INSERT,UPDATE,DELETE,TRUNCATE')) THEN RAISE EXCEPTION 'doors must not have schema CREATE or direct table write permissions'; END IF; END $permissions$;\n";
  String role="DO $role$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='doors') THEN CREATE ROLE doors LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END $role$;\n"
   +"DO $connect$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO doors', current_database()); END $connect$;\n";
  return dbaNotes(s)+"-- DBA installation script: gepuser must exist; doors is created only if absent.\n-- Run as postgres / authorised DBA in the intended GePNIC database.\n-- Existing doors passwords and role settings are preserved. New doors has no password: set it separately using psql \\password doors before configuring the Agent.\n-- Existing objects require migration, not rerunning this creation script.\n"+ddl(s).replace("BEGIN;\n","BEGIN;\n"+role).replace("COMMIT;",grants+"COMMIT;");
 }
 public static String scopeUpgradeDdl(Spec s){
  String full=ddl(s),ns=schema(s),grant=ns+"."+object(s,"service_grant");
  int start=full.indexOf("CREATE FUNCTION "),end=full.indexOf("END $$;",start)+7;
  if(start<0||end<7)throw new IllegalStateException("Write function missing");
  return "-- DBA-only upgrade: preserves payloads, versions, receipts and existing EXECUTE grants.\n-- Old per-record grant tables are retained but no longer consulted.\nBEGIN;\nCREATE TABLE IF NOT EXISTS "+grant+"(instance_id text NOT NULL,client_id bigint NOT NULL,enabled boolean NOT NULL DEFAULT false,PRIMARY KEY(instance_id,client_id));\nREVOKE ALL ON TABLE "+grant+" FROM PUBLIC;\n"+full.substring(start,end).replaceFirst("CREATE FUNCTION ","CREATE OR REPLACE FUNCTION ")+"\nREVOKE ALL ON FUNCTION "+ns+"."+object(s,"store_document")+"("+signatureTypes(s)+") FROM PUBLIC;\nCOMMIT;\n-- Authorise each intended client/instance explicitly; no old record grants are broadened automatically.\n";
 }
 private static String renderDdl(Spec s){
  String ddl=legacyDdl(s);if(!extended(s))return ddl;
  String ns=schema(s),table=table(s);
  String oldColumns=String.join(",",s.fields().stream().map(f->"\""+f.column()+"\" "+f.type()+" GENERATED ALWAYS AS ((payload #>> "+path(f.path())+")::"+f.type()+") STORED").toList());
  ddl=ddl.replace(oldColumns,recordColumns(s));
  ddl=ddl.replace("SET lock_timeout='5s' AS $$","SET lock_timeout='5s' SET DateStyle='ISO, YMD' SET TimeZone='UTC' SET IntervalStyle='iso_8601' SET lc_monetary='C' AS $$");
  ddl=ddl.replace("DECLARE k jsonb:=","DECLARE extracted "+ns+"."+table+"%ROWTYPE; k jsonb:=");
  String columns=String.join(",",s.fields().stream().map(f->"\""+f.column()+"\"").toList());
  String values=String.join(",",s.fields().stream().map(f->"extracted.\""+f.column()+"\"").toList());
  String original="INSERT INTO "+ns+"."+table+"(instance_id,business_key,record_version,payload,client_id,request_id) VALUES(p_instance,k,old+1,p_payload,p_client,p_request);";
  String replacement="extracted:=jsonb_populate_record(NULL::"+ns+"."+table+", "+mappedSql(s)+");\nINSERT INTO "+ns+"."+table+"(instance_id,business_key,record_version,payload,client_id,request_id,"+columns+") VALUES(p_instance,k,old+1,p_payload,p_client,p_request,"+values+");";
  return ddl.replace(original,replacement);
 }
 private static String legacyDdl(Spec s){check(s);String ns=schema(s),table=table(s);
  String keys=String.join(",",s.businessKey().stream().map(k->s.fields().stream().filter(f->f.path().equals(k)).findFirst().orElseThrow().type().equals("numeric")?"((p_payload #>> "+path(k)+")::numeric)::text":"(p_payload #>> "+path(k)+")").toList());
  String columns=String.join(",",s.fields().stream().map(f->"\""+f.column()+"\" "+f.type()+" GENERATED ALWAYS AS ((payload #>> "+path(f.path())+")::"+f.type()+") STORED").toList());
  return "-- Apply as dedicated schema owner. Do not grant runtime table writes.\nBEGIN;\nCREATE SCHEMA "+ns+";\nREVOKE ALL ON SCHEMA "+ns+" FROM PUBLIC;\n"+
   "CREATE TABLE "+ns+"."+object(s,"service_grant")+"(instance_id text NOT NULL,client_id bigint NOT NULL,enabled boolean NOT NULL DEFAULT false,PRIMARY KEY(instance_id,client_id));\n"+
   "CREATE TABLE "+ns+"."+object(s,"head")+"(instance_id text,business_key jsonb,revision bigint NOT NULL DEFAULT 0,PRIMARY KEY(instance_id,business_key));\n"+
   "CREATE TABLE "+ns+"."+table+"(instance_id text NOT NULL,business_key jsonb NOT NULL,record_version bigint NOT NULL CHECK(record_version>0),payload json NOT NULL CHECK(json_typeof(payload)='object'),client_id bigint NOT NULL,request_id uuid NOT NULL,received_at timestamptz NOT NULL DEFAULT clock_timestamp(),"+columns+",PRIMARY KEY(instance_id,business_key,record_version));\n"+
   "CREATE TABLE "+ns+"."+object(s,"receipt")+"(instance_id text,client_id bigint,request_id uuid,fingerprint text NOT NULL,response jsonb NOT NULL,PRIMARY KEY(instance_id,client_id,request_id));\n"+
   "CREATE VIEW "+ns+"."+object(s,"latest")+" AS SELECT v.* FROM "+ns+"."+table+" v JOIN "+ns+"."+object(s,"head")+" h USING(instance_id,business_key) WHERE v.record_version=h.revision;\n"+
   "CREATE FUNCTION "+ns+"."+object(s,"store_document")+"(p_instance text,p_client bigint,p_request uuid,p_expected bigint,p_fingerprint text,p_payload json,p_definition text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"+ns+" SET lock_timeout='5s' AS $$\n"+
   "DECLARE k jsonb:=jsonb_build_array("+keys+"); old bigint; r "+ns+"."+object(s,"receipt")+"%ROWTYPE; answer jsonb; BEGIN\n"+
   "IF p_definition IS DISTINCT FROM "+literal(hash(s))+" OR p_expected IS NULL OR p_expected<0 OR p_expected=9223372036854775807 OR p_instance IS NULL OR p_instance !~ '^[A-Za-z0-9_-]{1,80}$' OR p_client IS NULL OR p_client<1 OR p_request IS NULL OR p_fingerprint IS NULL OR p_fingerprint !~ '^[a-f0-9]{64}$' OR json_typeof(p_payload) IS DISTINCT FROM 'object' OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(k) e WHERE e IS NULL OR btrim(e)='' OR e<>btrim(e) OR length(e)>200) THEN RAISE EXCEPTION 'Invalid document metadata' USING ERRCODE='22023'; END IF;\n"+
   "IF NOT EXISTS(SELECT 1 FROM "+ns+"."+object(s,"service_grant")+" WHERE instance_id=p_instance AND client_id=p_client AND enabled) THEN RAISE EXCEPTION 'Service and instance write access denied' USING ERRCODE='DW003'; END IF;\n"+
   "PERFORM pg_advisory_xact_lock(hashtextextended(p_instance||':'||p_client||':'||p_request,0));\nSELECT * INTO r FROM "+ns+"."+object(s,"receipt")+" WHERE instance_id=p_instance AND client_id=p_client AND request_id=p_request;\n"+
   "IF FOUND THEN IF r.fingerprint<>p_fingerprint THEN RAISE EXCEPTION 'Request changed' USING ERRCODE='DW002'; END IF; RETURN r.response||'{\"replayed\":true}'::jsonb; END IF;\n"+
   "INSERT INTO "+ns+"."+object(s,"head")+" VALUES(p_instance,k,0) ON CONFLICT DO NOTHING; SELECT revision INTO old FROM "+ns+"."+object(s,"head")+" WHERE instance_id=p_instance AND business_key=k FOR UPDATE;\n"+
   "IF old<>p_expected THEN RAISE EXCEPTION 'Version conflict' USING ERRCODE='DW001'; END IF;\n"+
   "INSERT INTO "+ns+"."+table+"(instance_id,business_key,record_version,payload,client_id,request_id) VALUES(p_instance,k,old+1,p_payload,p_client,p_request);\nUPDATE "+ns+"."+object(s,"head")+" SET revision=old+1 WHERE instance_id=p_instance AND business_key=k;\n"+
   "answer:=jsonb_build_object('outcome','STORED','instanceId',p_instance,'requestId',p_request,'recordVersion',old+1,'replayed',false);\nINSERT INTO "+ns+"."+object(s,"receipt")+" VALUES(p_instance,p_client,p_request,p_fingerprint,answer);RETURN answer; END $$;\n"+
   "REVOKE ALL ON ALL TABLES IN SCHEMA "+ns+" FROM PUBLIC;\nREVOKE ALL ON FUNCTION "+ns+"."+object(s,"store_document")+"(text,bigint,uuid,bigint,text,json,text) FROM PUBLIC;\nCOMMIT;\n"+
   "-- DBA: grant USAGE on this schema and EXECUTE on store_document to the runtime role.\n-- Provision service_grant once per approved instance/client for this service. Business keys identify records, not permissions.\n";
 }
}
