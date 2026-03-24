package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.gepnic.doors.masterapi.repository.ExternalRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationRequestService {

    private final ExternalRequestRepository repository;

    @Transactional
    public ExternalRequest submitRequest(String title, String agentId, String justification, 
                                        String sampleJson, String requestedBy, MultipartFile file) {
        
        ExternalRequest.ExternalRequestBuilder builder = ExternalRequest.builder()
                .requestTitle(title)
                .targetAgentId(agentId)
                .justification(justification)
                .sampleJson(sampleJson)
                .requestedBy(requestedBy)
                .status("SUBMITTED"); 

        if (file != null && !file.isEmpty()) {
            try {
                // Storing raw bytes directly
                builder.attachmentData(file.getBytes());
                builder.attachmentName(file.getOriginalFilename());
                builder.attachmentType(file.getContentType());
            } catch (IOException e) {
                log.error("File processing failed", e);
                throw new RuntimeException("Could not process attachment: " + e.getMessage());
            }
        }

        return repository.save(builder.build());
    }

    @Transactional
    public void approveAndSync(Long id, String approvedBy) {
        ExternalRequest request = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        
        request.setStatus("APPROVED");
        request.setApprovedBy(approvedBy); 
        repository.save(request);
    }

    @Transactional
    public void rejectRequest(Long id, String reason, String rejectedBy) {
        ExternalRequest request = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        
        request.setStatus("REJECTED");
        request.setRejectionReason(reason);
        request.setApprovedBy(rejectedBy); 
        
        repository.save(request);
    }
public List<ExternalRequest> getRequestsByUser(String username) {
    log.info("Fetching orchestration history for user: {}", username);
    return repository.findByRequestedByOrderByCreatedAtDesc(username);
}
 
    public List<ExternalRequest> getRequestsByStatus(String status) {
        String searchStatus = status;
        if ("PENDING".equalsIgnoreCase(status) || status == null || status.isEmpty()) {
            searchStatus = "SUBMITTED"; 
        }
        return repository.findByStatusOrderByCreatedAtDesc(searchStatus);
    }

    public ResponseEntity<Resource> downloadRequestAttachment(Long id) {
        // FIXED: Changed type to ExternalRequest to match your model
        ExternalRequest request = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found with id: " + id));

        // FIXED: Since you saved file.getBytes() in submitRequest, 
        // the data is already a byte[]. No Base64 decoding is needed.
        byte[] fileData = request.getAttachmentData();

        if (fileData == null) {
            throw new RuntimeException("No attachment found for this request.");
        }

        ByteArrayResource resource = new ByteArrayResource(fileData);

        // Map the MediaType dynamically based on the stored attachmentType (e.g., application/pdf)
        MediaType contentType = MediaType.parseMediaType(request.getAttachmentType() != null ? 
                                 request.getAttachmentType() : "application/octet-stream");

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + request.getAttachmentName() + "\"")
                .body(resource);
    }
}