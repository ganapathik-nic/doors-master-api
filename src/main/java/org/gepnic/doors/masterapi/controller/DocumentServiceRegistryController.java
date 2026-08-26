package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.DocumentServiceRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/document-services")
@RequiredArgsConstructor
public class DocumentServiceRegistryController {

    private final DocumentServiceRegistryService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list(), "Document services retrieved"));
    }

    @GetMapping("/options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
        return ResponseEntity.ok(ApiResponse.success(service.options(), "Document service options retrieved"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.create(body), "Document service registered"));
    }

    @PutMapping("/{serviceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long serviceId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.update(serviceId, body), "Document service updated"));
    }

    @PostMapping("/{serviceId}/activate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@PathVariable Long serviceId) {
        return ResponseEntity.ok(ApiResponse.success(service.setActive(serviceId, true), "Document service activated"));
    }

    @PostMapping("/{serviceId}/deactivate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(@PathVariable Long serviceId) {
        return ResponseEntity.ok(ApiResponse.success(service.setActive(serviceId, false), "Document service deactivated"));
    }
}
