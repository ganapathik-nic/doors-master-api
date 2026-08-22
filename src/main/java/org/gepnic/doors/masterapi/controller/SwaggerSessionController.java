package org.gepnic.doors.masterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.SwaggerSessionService;
import org.gepnic.doors.masterapi.service.TemplateContractService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SwaggerSessionController {

    private final SwaggerSessionService swaggerSessionService;
    private final TemplateContractService templateContractService;

    @PostMapping("/admin/swagger-sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body,
            Authentication authentication,
            HttpServletRequest request) {
        try {
            Long clientId = Long.valueOf(String.valueOf(body.get("clientId")));
            String uniqueName = String.valueOf(body.get("uniqueName"));
            @SuppressWarnings("unchecked")
            Map<String, Object> requestBody = (Map<String, Object>) body.get("body");
            Map<String, Object> launch = swaggerSessionService.createLaunch(
                    clientId,
                    uniqueName,
                    requestBody,
                    body.get("keyFingerprint") == null ? null : String.valueOf(body.get("keyFingerprint")),
                    authentication.getName(),
                    request.getHeader("User-Agent")
            );
            return noStore(ApiResponse.success(launch, "Single-use Swagger launch created"));
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (java.util.NoSuchElementException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException | ClassCastException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/master/gateway/swagger-sessions/exchange")
    public ResponseEntity<ApiResponse<Map<String, Object>>> exchange(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            Map<String, Object> session = swaggerSessionService.exchange(
                    body.get("launchToken"), request.getHeader("User-Agent"));
            return noStore(ApiResponse.success(session, "Swagger launch exchanged"));
        } catch (SecurityException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(ApiResponse.error(exception.getMessage(), HttpStatus.UNAUTHORIZED.value()));
        }
    }

    @GetMapping("/master/gateway/swagger-sessions/contract")
    public ResponseEntity<ApiResponse<Map<String, Object>>> selectedContract(
            @RequestHeader("X-DOORS-SWAGGER-SESSION") String sessionToken) {
        String uniqueName = swaggerSessionService.selectedUniqueName(sessionToken);
        return templateContractService.publishedContract(uniqueName)
                .map(value -> noStore(ApiResponse.success(value, "Selected template contract retrieved")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/master/gateway/swagger-sessions/contract/export")
    public ResponseEntity<Map<String, Object>> exportSelectedContract(
            @RequestHeader("X-DOORS-SWAGGER-SESSION") String sessionToken) {
        String uniqueName = swaggerSessionService.selectedUniqueName(sessionToken);
        return templateContractService.exportPublishedContract(uniqueName)
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(uniqueName.replaceAll("[^A-Za-z0-9._-]", "_") + "-contract.json")
                                .build().toString())
                        .body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResponse.error(message, status.value()));
    }
}
