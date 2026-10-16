package org.gepnic.doors.masterapi.inbound;
import jakarta.servlet.http.HttpServletRequest;
import org.gepnic.doors.inbound.InboundProtocol;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.nio.charset.*;
@RestController @RequestMapping("/api/v1/inbound")
public class InboundGatewayController {
    private final InboundGatewayService service;private final ApiClientRepository clients;private final InboundProperties properties;
    public InboundGatewayController(InboundGatewayService s,ApiClientRepository c,InboundProperties p){service=s;clients=c;properties=p;}
    @PostMapping(value="/{dataset}",consumes="application/json")
    public ResponseEntity<?> write(@PathVariable String dataset,HttpServletRequest request) throws Exception {
        if(!properties.isEnabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WRITE_DISABLED");
        var client=clients.findByApiKey(request.getHeader("X-API-KEY")).filter(c->Boolean.TRUE.equals(c.getIsActive())).orElseThrow(()->new SecurityException("Invalid client"));
        byte[] body=request.getInputStream().readNBytes(InboundProtocol.MAX_BODY+1);
        if(body.length>InboundProtocol.MAX_BODY)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"PAYLOAD_TOO_LARGE");
        String payload=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
        var s=new InboundProtocol.Submission(dataset,header(request,"X-DOORS-Instance-ID"),client.getClientId(),
            UUID.fromString(header(request,"Idempotency-Key")),Integer.parseInt(header(request,"X-DOORS-Schema-Version")),
            Long.parseLong(header(request,"X-DOORS-Expected-Version")),Long.parseLong(header(request,"X-DOORS-Timestamp")),payload);
        return service.execute(client,s,header(request,"X-DOORS-Signature"));
    }
    private static String header(HttpServletRequest request,String name){
        String value=request.getHeader(name);
        if(value==null || value.isBlank() || value.length()>2048)throw new IllegalArgumentException("Missing or invalid write header");
        return value;
    }
    @ExceptionHandler({IllegalArgumentException.class,CharacterCodingException.class})
    ResponseEntity<?> invalid(Exception e){return ResponseEntity.unprocessableEntity().body(Map.of("code","INVALID_WRITE_REQUEST","message","Check JSON and required write headers"));}
}
