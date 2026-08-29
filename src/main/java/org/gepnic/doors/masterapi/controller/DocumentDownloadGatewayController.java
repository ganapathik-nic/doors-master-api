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

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master/gateway/documents")
@RequiredArgsConstructor
public class DocumentDownloadGatewayController {

    private final DocumentDownloadOrchestrationService service;
    private final org.gepnic.doors.masterapi.service.DocumentManifestService manifestService;
    private final DocumentServiceRegistrationRepository documentServiceRepository;
    private final ApiClientResponseEncryptionService responseEncryptionService;
    private final org.gepnic.doors.masterapi.service.SwaggerSessionService swaggerSessionService;

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
            @RequestBody Map<String, Object> request) {
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
    }

    @PostMapping("/{policyCode}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String policyCode,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> request) {
        DocumentDownloadOrchestrationService.DownloadedDocument document =
                service.download(policyCode, apiKey, request);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(document.contentType())
                .contentLength(document.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-DOORS-AGENT", document.agentId())
                .header("X-DOORS-POLICY", document.policyCode())
                .header("X-DOORS-DOCUMENT-TYPE", document.documentType())
                .header("X-Content-Secure", "false")
                .body(document.content());
    }

    @Operation(
            summary = "Download a registered document as a file",
            description = "Returns the original document bytes. Swagger displays a Download file action for this response.",
            responses = @ApiResponse(responseCode = "200", description = "Binary document attachment",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))))
    @PostMapping(value = "/services/{serviceName}/download",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadRegisteredDocument(
            @PathVariable String serviceName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> request) {
        RegisteredDocumentServiceClient.DocumentPayload document =
                manifestService.downloadDocument(serviceName, apiKey, request);
        String fileName = String.valueOf(request.get("fileName"));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(document.contentType())
                .contentLength(document.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Secure", "false")
                .body(document.content());
    }
}
