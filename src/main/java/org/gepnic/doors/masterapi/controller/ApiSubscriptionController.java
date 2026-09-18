package org.gepnic.doors.masterapi.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.*;
import org.gepnic.doors.masterapi.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/master/api-subscriptions")
@RequiredArgsConstructor
public class ApiSubscriptionController {
    private final ApiSubscriptionService subscriptions;
    private final ApiSubscriberDashboardService dashboard;

    @GetMapping
    public ApiResponse<Map<String,Object>> overview(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        var data = dashboard.dashboard(null, from, to);
        data.put("agents", subscriptions.agents());
        return ApiResponse.success(data, "API subscriptions and egress retrieved");
    }
    @PutMapping("/users/{userId}/license")
    public ApiResponse<ApiLicense> save(@PathVariable long userId, @Valid @RequestBody ApiLicense license, Authentication auth) {
        return ApiResponse.success(subscriptions.save(userId, license, auth.getName()), "License and accounting saved");
    }
    @GetMapping("/users/{userId}/history")
    public ApiResponse<List<Map<String,Object>>> history(@PathVariable long userId) {
        return ApiResponse.success(subscriptions.history(userId), "License history");
    }
    @PutMapping("/users/{userId}/account")
    public ApiResponse<ApiLicense> account(@PathVariable long userId, @Valid @RequestBody ApiSubscriptionAccount value, Authentication auth) {
        return ApiResponse.success(subscriptions.saveAccount(userId,value,auth.getName()), "Agent mapping and service saved");
    }
    @PostMapping("/users/{userId}/periods")
    public ApiResponse<Map<String,Object>> addPeriod(@PathVariable long userId, @Valid @RequestBody ApiSubscriptionPeriod value, Authentication auth) {
        return ApiResponse.success(subscriptions.savePeriod(userId,null,value,auth.getName()), "Subscription period added");
    }
    @PutMapping("/users/{userId}/periods/{periodId}")
    public ApiResponse<Map<String,Object>> editPeriod(@PathVariable long userId, @PathVariable long periodId,
            @Valid @RequestBody ApiSubscriptionPeriod value, Authentication auth) {
        return ApiResponse.success(subscriptions.savePeriod(userId,periodId,value,auth.getName()), "Subscription period updated");
    }
    @PutMapping("/clients/{clientId}/schedule")
    public ApiResponse<ApiAccessSchedule> schedule(@PathVariable long clientId, @Valid @RequestBody ApiAccessSchedule schedule, Authentication auth) {
        return ApiResponse.success(subscriptions.saveSchedule(clientId, schedule, auth.getName()), "Access calendar saved");
    }
    @PutMapping("/users/{userId}/weekly-access")
    public ApiResponse<ApiWeeklyAccess> weeklyAccess(@PathVariable long userId, @Valid @RequestBody ApiWeeklyAccess value, Authentication auth) {
        return ApiResponse.success(subscriptions.saveWeeklyAccess(userId, value, auth.getName()), "Weekly access saved");
    }
    public record Notice(Long clientId, @NotBlank @Size(max=160) String title,
                         @NotBlank @Size(max=2000) String message,
                         @NotNull @Pattern(regexp="INFO|WARNING|CRITICAL") String severity,
                         @NotNull OffsetDateTime expiresAt) {}
    @PostMapping("/notices")
    public ApiResponse<Integer> publish(@Valid @RequestBody Notice notice, Authentication auth) {
        return ApiResponse.success(dashboard.publish(notice.clientId(), notice.title(), notice.message(),
                notice.severity(), notice.expiresAt(), auth.getName()), "Dashboard notice published");
    }
}
