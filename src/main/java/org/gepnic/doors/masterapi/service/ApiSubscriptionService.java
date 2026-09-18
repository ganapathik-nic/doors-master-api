package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.*;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ApiSubscriptionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    // A configured subscriber covers every key mapped to their one licensed Agent.
    // Before configuration the existing explicit client assignments provide dashboard visibility.
    public static final String SUBSCRIBER_CLIENTS = """
        (SELECT uc.user_id, uc.client_id FROM user_api_clients uc
         WHERE NOT EXISTS (SELECT 1 FROM api_user_licenses l WHERE l.user_id=uc.user_id)
         UNION SELECT l.user_id, c.client_id FROM api_user_licenses l
         JOIN user_authorized_agents m ON m.agent_id=l.agent_id
         JOIN external_api_clients c ON c.client_name=m.user_name)
        """;

    public ApiLicense license(long userId) {
        var accounts = jdbc.queryForList("SELECT agent_id,service_enabled,version FROM api_user_licenses WHERE user_id=?", userId);
        if (accounts.isEmpty()) return null;
        var a = accounts.getFirst();
        // Compatibility read projection: current period, otherwise next, otherwise latest ended.
        var rows = periods(userId);
        var p = rows.stream().filter(r -> "CURRENT".equals(r.get("status"))).findFirst()
                .orElseGet(() -> rows.stream().filter(r -> "UPCOMING".equals(r.get("status")))
                        .min(Comparator.comparing(r -> r.get("validFrom").toString())).orElse(rows.isEmpty() ? null : rows.getFirst()));
        return new ApiLicense((String)a.get("agent_id"), p == null ? null : (LocalDate)p.get("validFrom"),
                p == null ? null : (LocalDate)p.get("validTo"), (Boolean)a.get("service_enabled"),
                p == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal)p.get("gepnicDue"),
                p == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal)p.get("gepnicPaid"),
                p == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal)p.get("doorsDue"),
                p == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal)p.get("doorsPaid"),
                p == null ? "" : (String)p.get("notes"), ((Number)a.get("version")).longValue());
    }
    public ApiLicensePolicy.Decision access(long clientId) {
        Boolean disabled=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM api_user_licenses l
                  JOIN user_authorized_agents m ON m.agent_id=l.agent_id
                  JOIN external_api_clients c ON c.client_name=m.user_name
                  WHERE c.client_id=? AND l.service_enabled=FALSE)
                """,Boolean.class,clientId);
        if(Boolean.TRUE.equals(disabled)) return new ApiLicensePolicy.Decision("DOORS-SERVICE-DISABLED",
                "DOORS API service is disabled for a GePNIC Agent mapped to this key. Contact the DataManager.",false);
        var now = Instant.now();
        var subscribers = jdbc.queryForList("""
                SELECT DISTINCT l.user_id FROM api_user_licenses l
                JOIN user_authorized_agents m ON m.agent_id=l.agent_id
                JOIN external_api_clients c ON c.client_name=m.user_name WHERE c.client_id=?
                """, Long.class, clientId);
        for (long userId : subscribers) {
            var decision = ApiWeeklyAccessPolicy.evaluate(weeklyAccess(userId), now);
            if (!decision.allowed()) return decision;
        }
        return ApiLicensePolicy.schedule(schedule(clientId),Instant.now());
    }
    public void enforce(long clientId) {
        var decision=access(clientId);
        if(!decision.allowed()) throw new DoorsApiException(HttpStatus.FORBIDDEN,decision.code(),"api-subscription",
                "API subscription access denied",decision.message(),false,Map.of());
    }
    /** Compatibility for an older open browser: existing accounting cannot be overwritten here. */
    @Transactional
    public ApiLicense save(long userId, ApiLicense value, String actor) {
        ApiLicensePolicy.validate(value);
        var previous = license(userId);
        if (previous != null && (!Objects.equals(value.validFrom(),previous.validFrom())
                || !Objects.equals(value.validTo(),previous.validTo()) || !Objects.equals(value.notes(),previous.notes())
                || value.gepnicDue().compareTo(previous.gepnicDue()) != 0 || value.gepnicPaid().compareTo(previous.gepnicPaid()) != 0
                || value.doorsDue().compareTo(previous.doorsDue()) != 0 || value.doorsPaid().compareTo(previous.doorsPaid()) != 0))
            throw new IllegalArgumentException("Subscription periods are now separate records. Refresh and use Add period or Edit period.");
        saveAccount(userId,new ApiSubscriptionAccount(value.agentId(),value.serviceEnabled(),value.version()),actor);
        if (previous == null) savePeriod(userId,null,new ApiSubscriptionPeriod(value.validFrom(),value.validTo(),value.gepnicDue(),
                value.gepnicPaid(),value.doorsDue(),value.doorsPaid(),value.notes(),0),actor);
        return license(userId);
    }
    @Transactional
    public ApiLicense saveAccount(long userId, ApiSubscriptionAccount value, String actor) {
        lockUser(userId);
        if (jdbc.queryForList("SELECT agent_id FROM agents WHERE agent_id=?",value.agentId()).isEmpty())
            throw new IllegalArgumentException("Select an existing GePNIC instance.");
        var previous = license(userId);
        if (value.version() != (previous == null ? 0 : previous.version())) throw conflict("Agent mapping or service status");
        jdbc.update("""
                INSERT INTO api_user_licenses(user_id,agent_id,service_enabled,version,updated_by) VALUES (?,?,?,?,?)
                ON CONFLICT(user_id) DO UPDATE SET agent_id=EXCLUDED.agent_id,service_enabled=EXCLUDED.service_enabled,
                  version=EXCLUDED.version,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """,userId,value.agentId(),value.serviceEnabled(),value.version()+1,actor);
        jdbc.update("INSERT INTO api_license_history(user_id,version,snapshot,changed_by) VALUES (?,?,?::jsonb,?)",
                userId,value.version()+1,json(new ApiSubscriptionAccount(value.agentId(),value.serviceEnabled(),value.version()+1)),actor);
        notifyAccount(value.agentId(),"API service account updated","Agent mapping or service status changed. Review your dashboard.",actor);
        return license(userId);
    }
    private void lockUser(long userId) {
        if (jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? AND lower(role)='apiuser' FOR UPDATE",userId).isEmpty())
            throw new NoSuchElementException("API user not found.");
    }
    private void notifyAccount(String agentId,String title,String message,String actor) {
        jdbc.update("""
                INSERT INTO api_subscriber_notices(client_id,title,message,severity,expires_at,created_by)
                SELECT DISTINCT c.client_id,?,?,'INFO',CURRENT_TIMESTAMP+INTERVAL '30 days',?
                FROM external_api_clients c JOIN user_authorized_agents m ON m.user_name=c.client_name WHERE m.agent_id=?
                """,title,message,actor,agentId);
    }
    public List<Map<String,Object>> periods(long userId) {
        var today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return jdbc.query("SELECT * FROM api_subscription_periods WHERE user_id=? ORDER BY valid_from DESC,period_id DESC", (rs,n) -> {
            var p = new LinkedHashMap<String,Object>();
            var from=rs.getObject("valid_from",LocalDate.class); var to=rs.getObject("valid_to",LocalDate.class);
            p.put("periodId",rs.getLong("period_id")); p.put("validFrom",from); p.put("validTo",to);
            p.put("gepnicDue",rs.getBigDecimal("gepnic_due")); p.put("gepnicPaid",rs.getBigDecimal("gepnic_paid"));
            p.put("doorsDue",rs.getBigDecimal("doors_due")); p.put("doorsPaid",rs.getBigDecimal("doors_paid"));
            p.put("notes",rs.getString("notes")); p.put("version",rs.getLong("version"));
            p.put("status",today.isBefore(from)?"UPCOMING":today.isAfter(to)?"ENDED":"CURRENT");
            p.put("daysRemaining",java.time.temporal.ChronoUnit.DAYS.between(today,to));
            return p;
        },userId);
    }
    @Transactional
    public Map<String,Object> savePeriod(long userId, Long periodId, ApiSubscriptionPeriod value, String actor) {
        if (value.validFrom()==null || value.validTo()==null || value.validTo().isBefore(value.validFrom()))
            throw new IllegalArgumentException("Period end must be on or after its start.");
        lockUser(userId); // Serializes overlapping-period checks and edits for this account.
        var account=license(userId);
        if (account==null) throw new IllegalArgumentException("Map this API user to an Agent before adding a subscription period.");
        var previous=periodId==null?null:periods(userId).stream().filter(p->((Number)p.get("periodId")).longValue()==periodId).findFirst()
                .orElseThrow(()->new NoSuchElementException("Subscription period does not belong to this API user."));
        if(value.version()!=(previous==null?0:((Number)previous.get("version")).longValue())) throw conflict("subscription period");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM api_subscription_periods WHERE user_id=? AND valid_from<=? AND valid_to>=? AND period_id<>?)",
                Boolean.class,userId,value.validTo(),value.validFrom(),periodId==null?0:periodId)))
            throw new IllegalArgumentException("This period overlaps an existing subscription period. Choose non-overlapping dates (both dates are inclusive).");
        long id;
        if(periodId==null) {
            id=jdbc.queryForObject("""
                    INSERT INTO api_subscription_periods(user_id,valid_from,valid_to,gepnic_due,gepnic_paid,doors_due,doors_paid,notes,version,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,1,?) RETURNING period_id
                    """,Long.class,userId,value.validFrom(),value.validTo(),value.gepnicDue(),value.gepnicPaid(),value.doorsDue(),value.doorsPaid(),value.notes(),actor);
        } else {
            id=periodId;
            jdbc.update("""
                    UPDATE api_subscription_periods SET valid_from=?,valid_to=?,gepnic_due=?,gepnic_paid=?,doors_due=?,doors_paid=?,notes=?,version=?,updated_by=?,updated_at=CURRENT_TIMESTAMP
                    WHERE period_id=? AND user_id=?
                    """,value.validFrom(),value.validTo(),value.gepnicDue(),value.gepnicPaid(),value.doorsDue(),value.doorsPaid(),value.notes(),value.version()+1,actor,id,userId);
        }
        var saved=periods(userId).stream().filter(p->((Number)p.get("periodId")).longValue()==id).findFirst().orElseThrow();
        jdbc.update("INSERT INTO api_subscription_period_history(period_id,user_id,version,snapshot,changed_by) VALUES (?,?,?,?::jsonb,?)",
                id,userId,saved.get("version"),json(saved),actor);
        notifyAccount(account.agentId(),"Subscription period updated","Subscription accounting for "+value.validFrom()+" to "+value.validTo()+" was updated. Dates and dues remain advisory.",actor);
        return saved;
    }
    public Map<String,Object> accountingTotals(long userId) {
        return jdbc.queryForMap("""
                SELECT COALESCE(SUM(gepnic_due),0) AS "gepnicDue",COALESCE(SUM(gepnic_paid),0) AS "gepnicPaid",
                  COALESCE(SUM(doors_due),0) AS "doorsDue",COALESCE(SUM(doors_paid),0) AS "doorsPaid",
                  COALESCE(SUM(GREATEST(gepnic_due-gepnic_paid,0)),0) AS "gepnicOutstanding",
                  COALESCE(SUM(GREATEST(doors_due-doors_paid,0)),0) AS "doorsOutstanding",
                  COALESCE(SUM(GREATEST(gepnic_paid-gepnic_due,0)),0) AS "gepnicCredit",
                  COALESCE(SUM(GREATEST(doors_paid-doors_due,0)),0) AS "doorsCredit"
                FROM api_subscription_periods WHERE user_id=?
                """,userId);
    }
    public ApiAccessSchedule schedule(long clientId) {
        return jdbc.query("SELECT version,windows::text FROM api_client_schedules WHERE client_id=?",(rs,n)->{
            try { return new ApiAccessSchedule(rs.getLong("version"),mapper.readValue(rs.getString("windows"),new TypeReference<List<ApiAccessSchedule.Window>>(){})); }
            catch(Exception ex) { throw new IllegalStateException("Invalid stored API access calendar",ex); }
        },clientId).stream().findFirst().orElse(new ApiAccessSchedule(0,List.of()));
    }
    @Transactional
    public ApiAccessSchedule saveSchedule(long clientId,ApiAccessSchedule value,String actor) {
        ApiLicensePolicy.validate(value);
        if(jdbc.queryForList("SELECT client_id FROM external_api_clients WHERE client_id=? FOR UPDATE",clientId).isEmpty())
            throw new NoSuchElementException("API client not found.");
        if(schedule(clientId).version()!=value.version()) throw conflict("calendar");
        String windows=json(value.windows());
        jdbc.update("""
                INSERT INTO api_client_schedules(client_id,version,windows,updated_by) VALUES (?,?,?::jsonb,?)
                ON CONFLICT(client_id) DO UPDATE SET version=EXCLUDED.version,windows=EXCLUDED.windows,
                  updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """,clientId,value.version()+1,windows,actor);
        jdbc.update("INSERT INTO api_client_schedule_history(client_id,version,windows,changed_by) VALUES (?,?,?::jsonb,?)",
                clientId,value.version()+1,windows,actor);
        jdbc.update("""
                INSERT INTO api_subscriber_notices(client_id,title,message,severity,expires_at,created_by)
                VALUES (?,'API access calendar updated',?,'INFO',CURRENT_TIMESTAMP+INTERVAL '30 days',?)
                """,clientId,value.windows().isEmpty()?"Calendar restrictions were removed. Access is unrestricted by time; service suspension still applies.":
                "The allowed date-and-time periods have changed. See the access calendar on your dashboard.",actor);
        return schedule(clientId);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalStateException("Unable to record subscription change",ex); }
    }
    public ApiWeeklyAccess weeklyAccess(long userId) {
        return jdbc.query("SELECT version,restricted,slots::text FROM api_user_weekly_access WHERE user_id=?", (rs,n) -> {
            try { return new ApiWeeklyAccess(rs.getLong("version"), rs.getBoolean("restricted"),
                    mapper.readValue(rs.getString("slots"), new TypeReference<List<ApiWeeklyAccess.Slot>>(){})); }
            catch (Exception ex) { throw new IllegalStateException("Invalid stored weekly API access schedule", ex); }
        }, userId).stream().findFirst().orElse(new ApiWeeklyAccess(0, false, List.of()));
    }
    @Transactional
    public ApiWeeklyAccess saveWeeklyAccess(long userId, ApiWeeklyAccess value, String actor) {
        ApiWeeklyAccessPolicy.validate(value);
        if (jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? AND lower(role)='apiuser' FOR UPDATE", userId).isEmpty())
            throw new NoSuchElementException("API user not found.");
        var license = license(userId);
        if (license == null) throw new IllegalArgumentException("Map this API user to an Agent in Subscriptions & Agent mapping first.");
        if (weeklyAccess(userId).version() != value.version()) throw conflict("weekly access schedule");
        jdbc.update("""
                INSERT INTO api_user_weekly_access(user_id,restricted,slots,version,updated_by) VALUES (?,?,?::jsonb,?,?)
                ON CONFLICT(user_id) DO UPDATE SET restricted=EXCLUDED.restricted,slots=EXCLUDED.slots,
                  version=EXCLUDED.version,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """, userId, value.restricted(), json(value.slots()), value.version()+1, actor);
        var saved = weeklyAccess(userId);
        jdbc.update("INSERT INTO api_user_weekly_access_history(user_id,version,snapshot,changed_by) VALUES (?,?,?::jsonb,?)",
                userId, saved.version(), json(saved), actor);
        jdbc.update("""
                INSERT INTO api_subscriber_notices(client_id,title,message,severity,expires_at,created_by)
                SELECT DISTINCT c.client_id,'Weekly API access updated',?,'INFO',CURRENT_TIMESTAMP+INTERVAL '30 days',?
                FROM external_api_clients c JOIN user_authorized_agents m ON m.user_name=c.client_name WHERE m.agent_id=?
                """, "Weekly access for Agent " + license.agentId() + " changed. Check Weekly access on your dashboard (India time).", actor, license.agentId());
        return saved;
    }
    private DoorsApiException conflict(String item) {
        return new DoorsApiException(HttpStatus.CONFLICT,"DOORS-SUBSCRIPTION-CONFLICT","subscription-conflict",
                "Subscription changed","Another manager updated this "+item+". Refresh before saving.",false,Map.of());
    }
    public List<Map<String,Object>> clients(String subscriber) {
        String scope=subscriber==null?"":" WHERE EXISTS(SELECT 1 FROM "+SUBSCRIBER_CLIENTS+" uc JOIN users u ON u.user_id=uc.user_id WHERE uc.client_id=c.client_id AND u.username=?)";
        Object[] args=subscriber==null?new Object[]{}:new Object[]{subscriber};
        var rows=jdbc.queryForList("SELECT c.client_id AS \"clientId\",c.client_name AS \"clientName\",c.is_active AS \"clientActive\","
                +"(SELECT string_agg(u.username,', ' ORDER BY u.username) FROM "+SUBSCRIBER_CLIENTS+" uc JOIN users u ON u.user_id=uc.user_id WHERE uc.client_id=c.client_id AND lower(u.role)='apiuser') AS subscribers FROM external_api_clients c"
                +scope+" ORDER BY c.client_name",args);
        for(var row:rows) {
            long id=((Number)row.get("clientId")).longValue();
            row.put("access",Boolean.TRUE.equals(row.get("clientActive"))?access(id):new ApiLicensePolicy.Decision("CLIENT-INACTIVE","API client is inactive.",false));
            row.put("schedule",schedule(id));
            row.put("agents",jdbc.queryForList("SELECT agent_id FROM user_authorized_agents WHERE user_name=? ORDER BY agent_id",String.class,row.get("clientName")));
        }
        return rows;
    }
    public List<Map<String,Object>> licenses(String subscriber) {
        var rows=jdbc.queryForList("SELECT u.user_id AS \"userId\",u.username FROM users u WHERE lower(u.role)='apiuser'"
                +(subscriber==null?"":" AND u.username=?")+" ORDER BY u.username",subscriber==null?new Object[]{}:new Object[]{subscriber});
        for(var row:rows) {
            var value=license(((Number)row.get("userId")).longValue());
            row.put("license",value);row.put("access",ApiLicensePolicy.evaluate(value,Instant.now()));
            var periods=periods(((Number)row.get("userId")).longValue());
            row.put("periods",periods);
            row.put("currentPeriod",periods.stream().filter(p->"CURRENT".equals(p.get("status"))).findFirst().orElse(null));
            row.put("nextPeriod",periods.stream().filter(p->"UPCOMING".equals(p.get("status"))).min(Comparator.comparing(p->p.get("validFrom").toString())).orElse(null));
            row.put("accountingTotals",accountingTotals(((Number)row.get("userId")).longValue()));
            var weekly = weeklyAccess(((Number)row.get("userId")).longValue());
            row.put("weeklyAccess", weekly);
            row.put("weeklyStatus", ApiWeeklyAccessPolicy.evaluate(weekly, Instant.now()));
            if(value!=null) {
                row.put("agentName",jdbc.queryForObject("SELECT display_name FROM agents WHERE agent_id=?",String.class,value.agentId()));
                if(value.validTo()!=null) row.put("daysRemaining",java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("Asia/Kolkata")),value.validTo()));
                row.put("coveredClients",jdbc.queryForList("SELECT DISTINCT c.client_name FROM external_api_clients c JOIN user_authorized_agents m ON m.user_name=c.client_name WHERE m.agent_id=? ORDER BY c.client_name",String.class,value.agentId()));
            }
        }
        return rows;
    }
    public List<Map<String,Object>> agents() {
        return jdbc.queryForList("SELECT agent_id AS \"agentId\",display_name AS \"displayName\" FROM agents ORDER BY display_name");
    }
    public List<Map<String,Object>> history(long userId) {
        return jdbc.queryForList("""
                SELECT version,snapshot::text AS snapshot,changed_by AS "changedBy",changed_at AS "changedAt",'Account / legacy' AS "changeType"
                FROM api_license_history WHERE user_id=?
                UNION ALL
                SELECT version,snapshot::text,changed_by,changed_at,'Period #' || period_id
                FROM api_subscription_period_history WHERE user_id=?
                ORDER BY "changedAt" DESC LIMIT 200
                """,userId,userId);
    }
}
