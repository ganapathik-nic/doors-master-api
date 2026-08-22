package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.TemplateContractService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master/gateway/template-contracts")
@RequiredArgsConstructor
public class TemplateContractController {

    private final TemplateContractService contractService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalogue() {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.publishedCatalogue(),
                "Published template contracts retrieved"
        ));
    }

    @GetMapping("/{uniqueName}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> contract(
            @PathVariable String uniqueName) {
        return contractService.publishedContract(uniqueName)
                .map(value -> ResponseEntity.ok(ApiResponse.success(value, "Template contract retrieved")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{uniqueName}/export")
    public ResponseEntity<Map<String, Object>> export(@PathVariable String uniqueName) {
        return contractService.exportPublishedContract(uniqueName)
                .map(value -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(uniqueName.replaceAll("[^A-Za-z0-9._-]", "_") + "-contract.json")
                                .build().toString())
                        .body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
