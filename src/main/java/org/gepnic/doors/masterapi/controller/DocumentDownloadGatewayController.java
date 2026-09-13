package org.gepnic.doors.masterapi.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.service.DocumentDownloadOrchestrationService;
import org.gepnic.doors.masterapi.service.ApiClientResponseEncryptionService;
import org.gepnic.doors.masterapi.service.RegisteredDocumentServiceClient;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.service.DocumentDownloadAuditService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/master/gateway/documents")
@RequiredArgsConstructor
public class DocumentDownloadGatewayController {

    private final DocumentDownloadOrchestrationService service;
    private final org.gepnic.doors.masterapi.service.DocumentManifestService manifestService;
    private final DocumentServiceRegistrationRepository documentServiceRepository;
    private final ApiClientResponseEncryptionService responseEncryptionService;
    private final org.gepnic.doors.masterapi.service.SwaggerSessionService swaggerSessionService;
    private final ApiClientRepository apiClientRepository;
    private final DocumentDownloadAuditService documentAuditService;
    private final RegisteredDocumentServiceClient documentServiceClient;

    @PostMapping("/services/{serviceName}/queries/{queryName}")
    public ResponseEntity<?> executeMappedQuery(
            @PathVariable String serviceName,
            @PathVariable String queryName,
            @RequestHeader("X-API-KEY") String apiKey,
            @Parameter(hidden = true)
            @RequestHeader(value = "X-DOORS-DOCUMENT-PREVIEW", defaultValue = "false") boolean previewDocumentCalls,
            @Parameter(hidden = true)
            @RequestHeader(value = "X-DOORS-SWAGGER-SESSION", required = false) String swaggerSession,
            @Parameter(hidden = true)
            @RequestHeader(value = "X-DOORS-REQUIRE-ENCRYPTED-RESPONSE", defaultValue = "false")
            boolean requireEncryptedResponse,
            HttpServletRequest servletRequest,
            @RequestBody Map<String, Object> request) {
        Instant startedAt = Instant.now();
        try {
            boolean activeSwaggerSession = swaggerSessionService.isActiveSession(swaggerSession);
            if (previewDocumentCalls && !activeSwaggerSession) {
                throw new SecurityException("Document call preview is available only in an active DOORS Swagger session");
            }
            if (requireEncryptedResponse && !activeSwaggerSession) {
                throw new SecurityException("Encrypted-response override is available only in an active DOORS Swagger session");
            }
            Map<String, Object> response = manifestService.discover(
                    serviceName, queryName, apiKey, request, previewDocumentCalls);
            var registration = documentServiceRepository.findByServiceNameIgnoreCase(serviceName)
                    .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "Active document service not found: " + serviceName));
            boolean encryptResponse = "AES".equalsIgnoreCase(registration.getPayloadMode())
                    || requireEncryptedResponse;
            if (!encryptResponse) {
                return ResponseEntity.ok()
                        .header("X-Content-Secure", "false")
                        .header("X-DOORS-PAYLOAD-MODE", "PLAIN_TEXT")
                        .body(response);
            }
            ApiClientResponseEncryptionService.EncryptedResponse encrypted =
                    responseEncryptionService.encrypt(apiKey, response);
            return ResponseEntity.ok()
                    .header("X-Content-Secure", "true")
                    .header("X-DOORS-PAYLOAD-MODE", "AES")
                    .header("X-DOORS-KID", encrypted.keyId())
                    .body(encrypted.envelope());
        } catch (RuntimeException error) {
            recordManifestFailure(serviceName, queryName, apiKey, request, servletRequest, startedAt, error);
            throw error;
        }
    }

    private void recordManifestFailure(String serviceName, String queryName, String apiKey,
                                       Map<String, Object> request, HttpServletRequest servletRequest,
                                       Instant startedAt, RuntimeException error) {
        var client = apiClientRepository.findByApiKey(apiKey).orElse(null);
        var registration = documentServiceRepository.findByServiceNameIgnoreCase(serviceName).orElse(null);
        int status = error instanceof SecurityException ? 403
                : error instanceof java.util.NoSuchElementException ? 404
                : error instanceof org.gepnic.doors.masterapi.exception.DoorsApiException doorsError
                ? doorsError.getStatus().value() : 500;
        String code = status == 403 ? "MANIFEST_ACCESS_DENIED"
                : status == 404 ? "DOCUMENT_SERVICE_INACTIVE_OR_NOT_FOUND"
                : "MANIFEST_QUERY_FAILED";
        documentAuditService.record(
                UUID.randomUUID().toString(),
                client == null ? "UNKNOWN" : client.getClientName(),
                serviceName,
                queryName,
                registration == null ? null : registration.getAgentId(),
                registration == null ? null : registration.getDocumentDownloadPolicyCode(),
                resolveClientIp(servletRequest), request, startedAt,
                "MANIFEST_FAILED", status, null, null, null, null,
                code, error.getMessage(), ensureTraceId(servletRequest), null,
                servletRequest.getRequestURL().toString(), null);
    }

    @PostMapping("/{policyCode}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String policyCode,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> request) {
        DocumentDownloadOrchestrationService.DownloadedDocument document =
                service.download(policyCode, apiKey, request);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(document.contentType())
                .contentLength(document.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-DOORS-AGENT", document.agentId())
                .header("X-DOORS-POLICY", document.policyCode())
                .header("X-DOORS-DOCUMENT-TYPE", document.documentType())
                .header("X-DOORS-SHA256", document.sha256())
                .header("X-Content-Secure", "false")
                .body(output -> {
                    try (document; var input = document.openStream()) { input.transferTo(output); }
                });
    }

    @Operation(
            summary = "Download a registered document as a file",
            description = "Returns the original document bytes. Swagger displays a Download file action for this response.",
            responses = @ApiResponse(responseCode = "200", description = "Binary document attachment",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))))
    @PostMapping(value = "/services/{serviceName}/download",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> downloadRegisteredDocument(
            @PathVariable String serviceName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String requestedCorrelationId,
            HttpServletRequest servletRequest,
            @RequestBody Map<String, Object> request) {
        Instant startedAt = Instant.now();
        String correlationId = correlationId(requestedCorrelationId);
        servletRequest.setAttribute("X-DOORS-CORRELATION", correlationId);
        String traceId = ensureTraceId(servletRequest);
        String clientIp = resolveClientIp(servletRequest);
        var client = apiClientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        var registration = documentServiceRepository.findByServiceNameIgnoreCase(serviceName)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new java.util.NoSuchElementException("Active document service not found: " + serviceName));
        String upstreamEndpoint = upstreamEndpoint(registration, request);
        RegisteredDocumentServiceClient.DocumentPayload document;
        try {
            document = manifestService.downloadDocument(serviceName, apiKey, request);
        } catch (RuntimeException error) {
            documentAuditService.record(correlationId, client.getClientName(), serviceName,
                    registration.getManifestQueryName(), registration.getAgentId(),
                    registration.getDocumentDownloadPolicyCode(), clientIp, request, startedAt,
                    "FAILED", 502, null, null, integer(request.get("queryResponseCode")), upstreamStatus(error),
                    "DOCUMENT_FETCH_FAILED", error.getMessage(), traceId, null,
                    servletRequest.getRequestURL().toString(), upstreamEndpoint);
            throw error;
        }
        String fileName = String.valueOf(request.get("fileName"));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(document.contentType())
                .contentLength(document.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Correlation-ID", correlationId)
                .header("X-DOORS-SHA256", document.sha256())
                .header("X-Content-Secure", "false")
                .body(output -> {
                    try (document; var input = document.openStream()) {
                        input.transferTo(output);
                        output.flush();
                        documentAuditService.record(correlationId, client.getClientName(), serviceName,
                                registration.getManifestQueryName(), registration.getAgentId(),
                                registration.getDocumentDownloadPolicyCode(), clientIp, request,
                                startedAt, "STREAMED", 200, document.contentLength(), document.sha256(),
                                integer(request.get("queryResponseCode")), document.responseCode(), null, null,
                                traceId, "DOCUMENT_DOWNLOAD_SUCCESS",
                                servletRequest.getRequestURL().toString(), document.upstreamEndpoint());
                    } catch (Exception error) {
                        documentAuditService.record(correlationId, client.getClientName(), serviceName,
                                registration.getManifestQueryName(), registration.getAgentId(),
                                registration.getDocumentDownloadPolicyCode(), clientIp, request,
                                startedAt, "STREAM_FAILED", 500, null, document.sha256(),
                                integer(request.get("queryResponseCode")), document.responseCode(),
                                "DOCUMENT_STREAM_FAILED", error.getMessage(), traceId, null,
                                servletRequest.getRequestURL().toString(), document.upstreamEndpoint());
                        throw error;
                    }
                });
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp.trim();
    }

    private static Integer integer(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String upstreamEndpoint(
            org.gepnic.doors.masterapi.model.DocumentServiceRegistration registration,
            Map<String, Object> request) {
        try {
            return documentServiceClient.buildDownloadUri(
                    registration,
                    requiredText(request.get("downloadId")),
                    requiredText(request.get("docCode")),
                    requiredText(request.get("fileName")),
                    optionalText(request.get("packetType"))).toASCIIString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer upstreamStatus(RuntimeException error) {
        if (error.getMessage() == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:HTTP|status)[ =:]+(\\d{3})", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(error.getMessage());
        if (!matcher.find()) return null;
        try { return Integer.valueOf(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String requiredText(Object value) {
        String text = optionalText(value);
        if (text.isBlank()) throw new IllegalArgumentException("Required document parameter is missing");
        return text;
    }

    private static String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String ensureTraceId(HttpServletRequest request) {
        Object existing = request.getAttribute("X-DOORS-TRACE");
        if (existing != null && !existing.toString().isBlank()) return existing.toString();
        String traceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        request.setAttribute("X-DOORS-TRACE", traceId);
        return traceId;
    }

    @PostMapping("/downloads/{correlationId}/receipt")
    public ResponseEntity<Void> recordDownloadReceipt(
            @PathVariable String correlationId,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> receipt) {
        var client = apiClientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        String source = required(receipt, "sourceSha256");
        String destination = required(receipt, "destinationSha256");
        documentAuditService.recordReceipt(correlationId, client.getClientName(), source, destination,
                source.equalsIgnoreCase(destination));
        return ResponseEntity.noContent().build();
    }

    private static String correlationId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{8,64}")) throw new IllegalArgumentException("Invalid X-Correlation-ID");
        return normalized;
    }

    private static String required(Map<String, Object> body, String field) {
        Object value = body.get(field);
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        return text.toLowerCase(java.util.Locale.ROOT);
    }
}
