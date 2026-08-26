package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.DocumentDownloadPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/governance/document-policies")
@RequiredArgsConstructor
public class DocumentDownloadPolicyController {

    private final DocumentDownloadPolicyService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list(), "Document policies retrieved"));
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long policyId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(policyId), "Document policy retrieved"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.create(body), "Document policy draft created"));
    }

    @PutMapping("/{policyId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long policyId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(policyId, body), "Document policy draft updated"));
    }

    @PostMapping("/{policyId}/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(@PathVariable Long policyId) {
        return ResponseEntity.ok(ApiResponse.success(service.validatePolicy(policyId), "Document policy is structurally valid"));
    }

    @PostMapping("/{policyId}/activate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@PathVariable Long policyId) {
        return ResponseEntity.ok(ApiResponse.success(service.activate(policyId), "Document policy activated"));
    }

    @PostMapping("/{policyId}/deactivate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(@PathVariable Long policyId) {
        return ResponseEntity.ok(ApiResponse.success(service.deactivate(policyId), "Document policy deactivated"));
    }
}
