package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.ApiSubscriberDashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/external/api-user/subscription")
@RequiredArgsConstructor
public class ApiSubscriberDashboardController {
    private final ApiSubscriberDashboardService service;
    @GetMapping
    public ApiResponse<Map<String,Object>> dashboard(@RequestParam LocalDate from, @RequestParam LocalDate to, Authentication auth) {
        return ApiResponse.success(service.dashboard(auth.getName(), from, to), "Your API subscription dashboard");
    }
    @PostMapping("/notices/{noticeId}/read")
    public ApiResponse<Void> read(@PathVariable long noticeId, Authentication auth) {
        service.markRead(noticeId, auth.getName());
        return ApiResponse.success(null, "Notice marked as read");
    }
}
