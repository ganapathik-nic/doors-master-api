package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MASTER: Dashboard Controller
 * Enhanced to support Infrastructure, User Management, and Governance metrics.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/master/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final JdbcTemplate jdbcTemplate;
    private final SqlTemplateRepository sqlTemplateRepository;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        log.info("DOORS-MASTER: Generating unified operational metrics for Admin");
        Map<String, Object> stats = new HashMap<>();

        try {
            // 1. Query Lifecycle Distribution (Pie Chart & Approved/Pending/Rejected KPIs)
            List<Object[]> lifecycleRaw = sqlTemplateRepository.countByStatusGrouped();
            stats.put("lifecycle", lifecycleRaw.stream().map(record -> {
                Map<String, Object> map = new HashMap<>();
                map.put("status", record[0]);
                map.put("count", record[1]);
                return map;
            }).toList());

            // 2. Activity Trends (Last 7 Days)
            String trendSql = """
                SELECT TO_CHAR(performed_at, 'YYYY-MM-DD') as day, COUNT(*) as count 
                FROM governance_audit_logs 
                WHERE performed_at > CURRENT_DATE - INTERVAL '7 days' 
                GROUP BY day ORDER BY day ASC
                """;
            stats.put("activityTrend", jdbcTemplate.queryForList(trendSql));

            // 3. Infrastructure & User KPIs (New Additions)
            
            // Count Active Remote Agents
            Integer activeAgents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE is_active = true", Integer.class);
            stats.put("activeAgents", activeAgents != null ? activeAgents : 0);

            // Count Registered API Clients
            Integer apiClients = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_api_clients", Integer.class);
            stats.put("apiClients", apiClients != null ? apiClients : 0);

            // Count Total Users
            Integer totalUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class);
            stats.put("totalUsers", totalUsers != null ? totalUsers : 0);

            // Count Users Pending Approval (where is_approved is false)
            Integer pendingUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE status ='APPROVED' ", Integer.class);
            stats.put("pendingUsers", pendingUsers != null ? pendingUsers : 0);

            // 4. Recent Governance Activity (Last 5 actions)
            String recentSql = """
                SELECT a.action_type, a.performed_by, t.unique_name as query_name, a.performed_at 
                FROM governance_audit_logs a 
                LEFT JOIN sql_templates t ON a.query_id = t.query_id 
                ORDER BY a.performed_at DESC LIMIT 5
                """;
            stats.put("recentActions", jdbcTemplate.queryForList(recentSql));

            return ResponseEntity.ok(ApiResponse.success(stats, "Comprehensive dashboard metrics loaded"));

        } catch (Exception e) {
            log.error("DOORS-MASTER: Dashboard aggregation error: {}", e.getMessage());
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Dashboard calculation failed: " + e.getMessage(), 500));
        }
    }
}