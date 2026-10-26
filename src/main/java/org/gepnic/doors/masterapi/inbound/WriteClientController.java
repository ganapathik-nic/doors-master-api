package org.gepnic.doors.masterapi.inbound;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.security.Principal;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/write-clients")
public class WriteClientController {
 private final WriteClientService service;
 public WriteClientController(WriteClientService service){this.service=service;}
 @GetMapping public Object list(){return service.list();}
 @PostMapping public Object create(@RequestBody WriteClientService.Registration body,Principal actor){return Map.of("clientId",service.create(body,actor.getName()));}
 @PostMapping("/{id}/attach") public void attach(@PathVariable long id,@RequestBody WriteClientService.Registration body,Principal actor){service.attach(id,body,actor.getName());}
 @GetMapping("/{id}/security") public ResponseEntity<?> security(@PathVariable long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.security(id));}
 @PutMapping("/{id}/security") public void security(@PathVariable long id,@RequestBody WriteClientService.Security body,Principal actor){service.security(id,body,actor.getName());}
 @PostMapping("/{id}/rotate-key") public ResponseEntity<?> rotate(@PathVariable long id,Principal actor){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("apiKey",service.rotate(id,actor.getName())));}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) public ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("message","This client is already registered, or its details conflict with an existing record. Refresh the list."));}
}
