package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.gepnic.doors.masterapi.service.ApiSubscriptionService.SUBSCRIBER_CLIENTS;

@Service
@RequiredArgsConstructor
public class ApiSubscriberDashboardService {
    private final JdbcTemplate jdbc;
    private final ApiSubscriptionService subscriptions;

    public Map<String, Object> dashboard(String subscriber, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to))
            throw new IllegalArgumentException("Select a date range of at most 367 days, with the end on or after the start.");
        var clients = subscriptions.clients(subscriber);
        // Scope through EXISTS prevents double-counting when several portal users share a client key.
        String scoped = subscriber == null ? "" : " AND EXISTS (SELECT 1 FROM " + SUBSCRIBER_CLIENTS + " uc JOIN users u ON u.user_id=uc.user_id WHERE uc.client_id=e.client_id AND u.username=?)";
        var args = new ArrayList<Object>();
        args.add(from.atStartOfDay(ZoneId.of("Asia/Kolkata")).toOffsetDateTime());
        args.add(to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toOffsetDateTime());
        if (subscriber != null) args.add(subscriber);
        String where = " FROM api_egress_events e WHERE occurred_at >= ? AND occurred_at < ?" + scoped;
        String aggregates = """
                COUNT(*) AS requests,
                COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 300 AND transfer_complete) AS successful,
                COUNT(*) FILTER (WHERE status_code >= 400 OR NOT transfer_complete) AS failed,
                COALESCE(SUM(response_bytes),0) AS "responseBytes",
                COALESCE(SUM(response_bytes) FILTER (WHERE status_code >= 200 AND status_code < 300 AND transfer_complete),0) AS "successfulBytes",
                COALESCE(SUM(record_count) FILTER (WHERE status_code >= 200 AND status_code < 300 AND transfer_complete),0) AS records,
                COALESCE(ROUND(AVG(duration_ms)),0) AS "averageMs"
                """;
        var byClient = jdbc.queryForList("SELECT client_id AS \"clientId\", " + aggregates + where + " GROUP BY client_id", args.toArray());
        for (var client : clients) {
            var usage = byClient.stream().filter(row -> ((Number)row.get("clientId")).longValue() == ((Number)client.get("clientId")).longValue())
                    .findFirst().orElse(Map.of("requests",0,"successful",0,"failed",0,"responseBytes",0,"successfulBytes",0,"records",0,"averageMs",0));
            client.put("usage", usage);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("clients", clients);
        result.put("licenses", subscriptions.licenses(subscriber));
        result.put("totals", jdbc.queryForMap("SELECT " + aggregates + where, args.toArray()));
        result.put("daily", jdbc.queryForList("SELECT (occurred_at AT TIME ZONE 'Asia/Kolkata')::date AS day, "
                + aggregates + where + " GROUP BY day ORDER BY day", args.toArray()));
        result.put("endpoints", jdbc.queryForList("SELECT endpoint, " + aggregates + where
                + " GROUP BY endpoint ORDER BY \"responseBytes\" DESC LIMIT 20", args.toArray()));
        result.put("trackingStartedAt", jdbc.queryForObject("SELECT tracking_started_at FROM api_usage_metadata WHERE singleton=TRUE", OffsetDateTime.class));
        result.put("timeZone", "Asia/Kolkata");
        result.put("from", from); result.put("to", to);
        result.put("notices", notices(subscriber));
        var userArgs = new ArrayList<Object>(args.subList(0, 2));
        if (subscriber != null) userArgs.add(subscriber);
        var subscribers = jdbc.queryForList("""
                SELECT u.user_id AS "userId", u.username, COUNT(DISTINCT uc.client_id) AS "clientCount",
                  COALESCE(SUM(e.requests),0) AS requests, COALESCE(SUM(e.successful),0) AS successful,
                  COALESCE(SUM(e.failed),0) AS failed, COALESCE(SUM(e.bytes),0) AS "responseBytes",
                  COALESCE(SUM(e.records),0) AS records
                FROM users u LEFT JOIN %s uc ON uc.user_id=u.user_id
                LEFT JOIN (SELECT client_id, COUNT(*) AS requests,
                  COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 300 AND transfer_complete) AS successful,
                  COUNT(*) FILTER (WHERE status_code >= 400 OR NOT transfer_complete) AS failed,
                  SUM(response_bytes) AS bytes,
                  SUM(record_count) FILTER (WHERE status_code >= 200 AND status_code < 300 AND transfer_complete) AS records
                  FROM api_egress_events WHERE occurred_at >= ? AND occurred_at < ? GROUP BY client_id) e ON e.client_id=uc.client_id
                WHERE lower(u.role)='apiuser'
                """.formatted(SUBSCRIBER_CLIENTS) + (subscriber == null ? "" : " AND u.username=?") + " GROUP BY u.user_id, u.username ORDER BY \"responseBytes\" DESC, u.username", userArgs.toArray());
        for (var user : subscribers) {
            long userId = ((Number) user.get("userId")).longValue();
            var license = subscriptions.license(userId);
            user.put("agentId", license == null ? null : license.agentId());
            user.put("agentName", license == null ? null : jdbc.queryForObject(
                    "SELECT display_name FROM agents WHERE agent_id=?", String.class, license.agentId()));
            var clientIds = jdbc.queryForList("SELECT client_id FROM " + SUBSCRIBER_CLIENTS + " uc WHERE uc.user_id=?", Long.class, userId);
            user.put("clients", clients.stream().filter(client -> clientIds.contains(((Number) client.get("clientId")).longValue())).toList());
        }
        result.put("subscribers", subscribers);
        return result;
    }

    public List<Map<String,Object>> notices(String subscriber) {
        if (subscriber == null) return jdbc.queryForList("""
                SELECT n.notice_id AS "noticeId", n.client_id AS "clientId", c.client_name AS "clientName",
                  n.title, n.message, n.severity, n.expires_at AS "expiresAt", n.created_at AS "createdAt", n.created_by AS "createdBy"
                FROM api_subscriber_notices n JOIN external_api_clients c ON c.client_id=n.client_id
                WHERE expires_at > CURRENT_TIMESTAMP ORDER BY n.created_at DESC LIMIT 200
                """);
        return jdbc.queryForList("""
                SELECT n.notice_id AS "noticeId", n.client_id AS "clientId", c.client_name AS "clientName",
                  n.title, n.message, n.severity, n.expires_at AS "expiresAt", n.created_at AS "createdAt",
                  (r.read_at IS NOT NULL) AS "isRead"
                FROM api_subscriber_notices n JOIN external_api_clients c ON c.client_id=n.client_id
                JOIN %s uc ON uc.client_id=n.client_id JOIN users u ON u.user_id=uc.user_id
                LEFT JOIN api_subscriber_notice_reads r ON r.notice_id=n.notice_id AND r.user_id=u.user_id
                WHERE u.username=? AND n.expires_at > CURRENT_TIMESTAMP ORDER BY n.created_at DESC LIMIT 200
                """.formatted(SUBSCRIBER_CLIENTS), subscriber);
    }

    public void markRead(long noticeId, String subscriber) {
        int updated = jdbc.update("""
                INSERT INTO api_subscriber_notice_reads(notice_id, user_id)
                SELECT n.notice_id, u.user_id FROM api_subscriber_notices n
                JOIN %s uc ON uc.client_id=n.client_id JOIN users u ON u.user_id=uc.user_id
                WHERE n.notice_id=? AND u.username=? AND n.expires_at > CURRENT_TIMESTAMP
                ON CONFLICT(notice_id, user_id) DO UPDATE SET read_at=CURRENT_TIMESTAMP
                """.formatted(SUBSCRIBER_CLIENTS), noticeId, subscriber);
        if (updated == 0) throw new SecurityException("Notice is not available to this account.");
    }

    public int publish(Long clientId, String title, String message, String severity, OffsetDateTime expiresAt, String actor) {
        if (expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now()) || expiresAt.isAfter(OffsetDateTime.now().plusYears(1)))
            throw new IllegalArgumentException("Notice expiry must be within the next year.");
        String condition = clientId == null ? "" : " WHERE client_id=?";
        var args = new ArrayList<Object>(List.of(title.trim(), message.trim(), severity, expiresAt, actor));
        if (clientId != null) args.add(clientId);
        int count = jdbc.update("""
                INSERT INTO api_subscriber_notices(client_id, title, message, severity, expires_at, created_by)
                SELECT client_id, ?, ?, ?, ?, ? FROM external_api_clients
                """ + condition, args.toArray());
        if (count == 0) throw new IllegalArgumentException("No matching API clients.");
        return count;
    }
}
