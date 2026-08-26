package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.service.DocumentDownloadOrchestrationService;
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

    @PostMapping("/services/{serviceName}/queries/{queryName}")
    public ResponseEntity<Map<String, Object>> executeMappedQuery(
            @PathVariable String serviceName,
            @PathVariable String queryName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(manifestService.discover(serviceName, queryName, apiKey, request));
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
}
