package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.TemplateContractService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/template-contracts")
@RequiredArgsConstructor
public class TemplateContractGovernanceController {

    private final TemplateContractService contractService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalogue() {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.governanceCatalogue(), "Contract governance catalogue retrieved"));
    }

    @GetMapping("/{uniqueName}/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history(@PathVariable String uniqueName) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.history(uniqueName), "Contract history retrieved"));
    }

    @PostMapping("/{uniqueName}/discover")
    public ResponseEntity<ApiResponse<Map<String, Object>>> discover(
            @PathVariable String uniqueName,
            @RequestBody Map<String, Object> body) {
        Object samplePayload = body.get("samplePayload");
        if (samplePayload == null) throw new IllegalArgumentException("samplePayload is required for manual discovery");
        return ResponseEntity.ok(ApiResponse.success(
                contractService.captureManualObservation(uniqueName, samplePayload),
                "Contract structure discovered; sample values were not stored"));
    }

    @PutMapping("/{uniqueName}/draft")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateDraft(
            @PathVariable String uniqueName,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.updateDraft(uniqueName, body, actor(authentication)),
                "Contract draft saved"));
    }

    @PostMapping("/{uniqueName}/submit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @PathVariable String uniqueName,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.submit(uniqueName, actor(authentication)),
                "Contract submitted for review"));
    }

    @PostMapping("/{contractId}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @PathVariable Long contractId,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.approve(contractId, actor(authentication), comment(body)),
                "Contract approved and published"));
    }

    @PostMapping("/{contractId}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @PathVariable Long contractId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String comment = comment(body);
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("Review comment is required");
        return ResponseEntity.ok(ApiResponse.success(
                contractService.reject(contractId, actor(authentication), comment),
                "Contract rejected for revision"));
    }

    @PostMapping("/{contractId}/restore")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restore(
            @PathVariable Long contractId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.restore(contractId, actor(authentication)),
                "Selected contract version restored as a new draft"));
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "SYSTEM" : authentication.getName();
    }

    private String comment(Map<String, Object> body) {
        if (body == null || body.get("comment") == null) return null;
        return String.valueOf(body.get("comment")).trim();
    }
}
