package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.DataPullRequestDTO;
import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.gepnic.doors.masterapi.service.DataPullService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j; 
import java.security.Principal;

@Slf4j
@RestController
@RequestMapping("/api/v1/external/data-pull")
public class DataPullController {

    @Autowired
    private DataPullService service;

    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitRequest(
            @RequestParam("requestTitle") String requestTitle,
            @RequestParam("targetAgentId") String targetAgentId,
            @RequestParam("justification") String justification,
            @RequestParam(value = "requestedBy", required = false) String ignoredRequestedBy,
            @RequestParam(value = "sampleJson", required = false) String sampleJson,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Principal principal) {
        
        try {
            DataPullRequestDTO dto = new DataPullRequestDTO();
            dto.setRequestTitle(requestTitle);
            dto.setTargetAgentId(targetAgentId);
            dto.setJustification(justification);
            dto.setRequestedBy(principal.getName());
            dto.setSampleJson(sampleJson);

            ExternalRequest savedRequest = service.submitRequest(dto, file);
            return ResponseEntity.ok(Map.of("success", true, "requestId", savedRequest.getId()));
        } catch (Exception e) {
            log.error("CRITICAL ERROR: ", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/my-list")
    public ResponseEntity<?> getMyRequests(
            @RequestParam(required = false) String username,
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.success(
            service.getRequestsByUser(principal.getName()),
            "History retrieved"
        ));
    }

    @GetMapping("/my-requests/{id}")
    public ResponseEntity<?> getMyRequest(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getMyRequest(id), "Request retrieved"));
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listByStatus(@RequestParam String status) {
        // The service now handles the mapping logic internally
        List<Map<String, Object>> result = service.getSummaryByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(result, "Fetched successfully"));
    }
}
