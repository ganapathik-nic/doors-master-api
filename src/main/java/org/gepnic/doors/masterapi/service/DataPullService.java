package org.gepnic.doors.masterapi.service;

import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.DataPullRequestDTO;
import org.gepnic.doors.masterapi.dto.MyDataRequestDetails;
import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.gepnic.doors.masterapi.repository.DataPullRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataPullService {

    @Autowired
    private DataPullRequestRepository repository;

    @Transactional(readOnly = true)
    public MyDataRequestDetails getMyRequest(Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            throw new SecurityException("Request access denied");
        }
        if (id == null || id <= 0) throw new SecurityException("Request access denied");
        // Scope the database lookup itself; privileged roles cannot bypass ownership here.
        var request = repository.findByIdAndRequestedBy(id, auth.getName())
                .orElseThrow(() -> new SecurityException("Request access denied"));
        return MyDataRequestDetails.from(request);
    }

    /**
     * Processes and stores a new data pull request with its binary attachment.
     */
    @Transactional
    public ExternalRequest submitRequest(DataPullRequestDTO dto, MultipartFile file) throws IOException {
        log.info("DOORS-SERVICE: Submitting new request for title: {}", dto.getRequestTitle());
        
        ExternalRequest request = new ExternalRequest();
        request.setRequestTitle(dto.getRequestTitle());
        request.setRequestedBy(dto.getRequestedBy());
        request.setTargetAgentId(dto.getTargetAgentId());
        request.setJustification(dto.getJustification());
        request.setSampleJson(dto.getSampleJson());
        request.setStatus("SUBMITTED");
        request.setCreatedAt(java.time.OffsetDateTime.now());

        // Handle binary file storage directly in the database BYTEA/BLOB column
        if (file != null && !file.isEmpty()) {
            request.setAttachmentData(file.getBytes());
            request.setAttachmentName(file.getOriginalFilename());
            request.setAttachmentType(file.getContentType());
        }

        return repository.save(request);
    }

    /**
     * Retrieves request history for a specific external user.
     */
    public List<MyDataRequestDetails> getRequestsByUser(String username) {
        log.info("DOORS-SERVICE: Fetching orchestration history for user: {}", username);
        return repository.findByRequestedByOrderByCreatedAtDesc(username).stream()
                .map(MyDataRequestDetails::from).toList();
    }

    /**
     * Retrieves all requests for Data Manager oversight with basic status filtering.
     */
    public List<ExternalRequest> getRequestsByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * Fulfills the requirement for the Governance Library / Data Manager Dashboard.
     * Converts raw DB rows into formatted maps for the UI.
     */
    public List<Map<String, Object>> getSummaryByStatus(String status) {
        log.info("DOORS-SERVICE: Fetching summary list for status: {}", status);
        
        // This calls the custom @Query in your repository
        List<Object[]> rawData = repository.findSummaryByStatus(status);
        
        return rawData.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row[0]);
            map.put("requestedBy", row[1]);
            map.put("targetAgentId", row[2]);
            map.put("status", row[3]);
            map.put("createdAt", row[4]);
            map.put("queryId", row[5]);
            // Safe null check for index 6 (rejection_reason)
            map.put("rejectionReason", row.length > 6 ? row[6] : null);
            return map;
        }).collect(Collectors.toList());
    }
}
