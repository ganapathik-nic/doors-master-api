package org.gepnic.doors.masterapi.inbound;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/write-integrations")
public class WriteIntegrationController {
 @org.springframework.beans.factory.annotation.Autowired private WriteEncryptionKeys encryptionKeys;
 @GetMapping("/encryption-key") public Object encryptionKey()throws Exception{return encryptionKeys.active();}
 private final WriteIntegrationService service;
 public WriteIntegrationController(WriteIntegrationService service){this.service=service;}
 @GetMapping("/{id}/guide") public Object guide(@PathVariable long id){return service.guide(id);}
 @GetMapping("/{id}/openapi") public Object openapi(@PathVariable long id)throws Exception{return service.openapi(id);}
 @GetMapping("/{id}/sdk") public ResponseEntity<byte[]> sdk(@PathVariable long id,@RequestParam(defaultValue="") String apiBaseUrl,@RequestParam(defaultValue="PROD") String profile)throws Exception{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Disposition","attachment; filename="+service.sdkDownloadName(id,profile)).contentType(MediaType.APPLICATION_OCTET_STREAM).body(service.sdk(id,apiBaseUrl,profile));}
 public record Scope(boolean enabled){}
 @PostMapping("/{id}/scope-sql") public Object scope(@PathVariable long id,@RequestBody Scope scope){return Map.of("sql",service.scopeSql(id,scope.enabled()));}
 @GetMapping("/{id}/scope-upgrade") public ResponseEntity<String> scopeUpgrade(@PathVariable long id){return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header("Content-Disposition","attachment; filename=write-scope-upgrade.sql").body(service.scopeUpgrade(id));}
 @GetMapping("/assets/{name}") public ResponseEntity<Resource> asset(@PathVariable String name)throws Exception{
  if(!Set.of("swagger-ui-bundle.js","swagger-ui.css","forge.js").contains(name))throw new IllegalArgumentException("Unknown asset");
  Resource resource;if(name.equals("forge.js"))resource=new ClassPathResource("static/vendor/forge-1.3.2.min.js");else{var resources=new PathMatchingResourcePatternResolver().getResources("classpath*:META-INF/resources/webjars/swagger-ui/*/"+name);if(resources.length==0)throw new IllegalStateException("Swagger asset missing");resource=resources[0];}
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(name.endsWith("css")?"text/css":"application/javascript")).body(resource);
 }
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
}
