package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.DocumentDownloadReportingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/governance/document-downloads")
@RequiredArgsConstructor
public class DocumentDownloadReportingController {
    private final DocumentDownloadReportingService service;

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String clientName,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String verification,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(service.logs(page, size, clientName, serviceName,
                agentId, outcome, verification, search, startDate, endDate), "Document download logs retrieved"));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary() {
        return ResponseEntity.ok(ApiResponse.success(service.dashboard(), "Document download summary retrieved"));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> filterOptions() {
        return ResponseEntity.ok(ApiResponse.success(service.filterOptions(),
                "Document download filter options retrieved"));
    }
}
