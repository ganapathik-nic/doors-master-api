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
/**
     * USER-SPECIFIC: Dashboard Stats
     * Used by External Users / DataViewers to see their own access footprint.
     */
  @GetMapping("/user/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserStats(java.security.Principal principal) {
        // principal.getName() usually returns the email/sub from your JWT
        String loginUser = principal.getName(); 
        log.info("DOORS-MASTER: Fetching personalized metrics for user: {}", loginUser);
        Map<String, Object> stats = new HashMap<>();

        try {
            String agentSql = """
                SELECT DISTINCT uaa.agent_id
                FROM user_authorized_agents uaa
                JOIN agents a ON a.agent_id = uaa.agent_id
                WHERE LOWER(TRIM(uaa.user_name)) = LOWER(TRIM(?))
                  AND a.is_active = true
                ORDER BY uaa.agent_id
                """;
        List<Map<String, Object>> mappedAgentsList = jdbcTemplate.queryForList(agentSql, loginUser);
        
        stats.put("mappedAgents", mappedAgentsList); // Send the whole list
        stats.put("mappedAgentsCount", mappedAgentsList.size());
            // 🚀 FIX 1: Use 'user_name' as per your \d output
            // 🚀 FIX 2: Added a check for 'user_id' just in case your system stores the ID there
            // 🚀 FIX 3: Robust Recent Reports query
            // If 'category' column fails, check if you named it 'category_name' in sql_templates
            String reportsSql = """
                SELECT DISTINCT t.unique_name,
                       COALESCE(t.category, 'Uncategorised') AS category_name,
                       t.created_at
                FROM sql_templates t
                JOIN sql_template_authorized_agents staa ON staa.query_id = t.query_id
                JOIN user_authorized_agents uaa ON uaa.agent_id = staa.agent_id
                JOIN agents a ON a.agent_id = staa.agent_id
                WHERE LOWER(TRIM(uaa.user_name)) = LOWER(TRIM(?))
                  AND UPPER(t.status) = 'APPROVED'
                  AND t.is_active = true
                  AND a.is_active = true
                ORDER BY t.created_at DESC
                LIMIT 5
                """;
            stats.put("recentReports", jdbcTemplate.queryForList(reportsSql, loginUser));

            return ResponseEntity.ok(ApiResponse.success(stats, "User stats loaded"));

        } catch (Exception e) {
            log.error("DOORS-MASTER: User stats database error: {}", e.getMessage());
            // Return the specific error message to help debugging during testing
            return ResponseEntity.status(500).body(ApiResponse.error("DB Error: " + e.getMessage(), 500));
        }
    }
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
            List<Map<String, Object>> latestQueryAdditions = jdbcTemplate.queryForList("""
                    SELECT created_at::date AS "queryDate", COUNT(*) AS count
                      FROM sql_templates
                     WHERE created_at::date = (SELECT MAX(created_at::date) FROM sql_templates)
                     GROUP BY created_at::date
                    """);
            stats.put("latestQueryAdditions",
                    latestQueryAdditions.isEmpty() ? Map.of() : latestQueryAdditions.get(0));

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

            // Count Active Users
            Integer activeUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users where status = 'ACTIVE'", Integer.class);
            stats.put("activeUsers", activeUsers != null ? activeUsers : 0);

            // Count Users Pending Approval (where is_approved is false)
            Integer pendingUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE status ='PENDING' ", Integer.class);
            stats.put("pendingUsers", pendingUsers != null ? pendingUsers : 0);
// Count Pending Data Requests (status != 'APPROVED')
//Integer pendingDataRequests = jdbcTemplate.queryForObject(
//    "SELECT COUNT(*) FROM data_pull_requests WHERE (status <> 'APPROVED'", Integer.class);
//stats.put("pendingDataRequests", pendingDataRequests != null ? pendingDataRequests : 0);
    
// ❌ INCORRECT (Syntax error, insert ")" to complete MethodInvocation)
// Integer pendingDataRequests = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM data_pull_requests WHERE (status = 'SUBMITTED'", Integer.class);

// ✅ CORRECT
Integer pendingDataRequests = jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM data_pull_requests WHERE status = 'SUBMITTED'", 
    Integer.class
);
stats.put("pendingDataRequests", pendingDataRequests != null ? pendingDataRequests : 0);
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
