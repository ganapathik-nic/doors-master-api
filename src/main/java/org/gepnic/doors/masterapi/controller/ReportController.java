package org.gepnic.doors.masterapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.service.QueryExecutionService;
import org.springframework.web.bind.annotation.*;

/**
 * ReportController handles legacy or internal UI-specific reporting needs.
 * * NOTE: The /orchestrate/{queryName} endpoint has been MOVED to 
 * PublicReportApiController to resolve Ambiguous Mapping and 
 * support X-API-KEY / manual userId passing for API consumers.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/master/reports")
public class ReportController {

    private final QueryExecutionService executionService;
    private final UserRepository userRepository;

    public ReportController(QueryExecutionService executionService, UserRepository userRepository) {
        this.executionService = executionService;
        this.userRepository = userRepository;
    }

    /* ORCHESTRATION LOGIC REMOVED FROM HERE 
       to resolve 'Ambiguous handler methods' conflict.
       Use PublicReportApiController for /orchestrate/{uniqueName} calls.
    */
    
    @GetMapping("/status")
    public String getStatus() {
        return "Report Controller is active. Use Public API for orchestration.";
    }
}