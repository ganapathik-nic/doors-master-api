package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.dto.ApiLicense;
import org.gepnic.doors.masterapi.dto.ApiAccessSchedule;
import org.gepnic.doors.masterapi.dto.ApiWeeklyAccess;
import org.gepnic.doors.masterapi.dto.ApiSubscriptionAccount;
import org.gepnic.doors.masterapi.dto.ApiSubscriptionPeriod;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in PostgreSQL verification: all tables are session-local TEMP tables, all work rolls back. */
@EnabledIfEnvironmentVariable(named="DOORS_SUBSCRIPTION_DB_TESTS", matches="true")
class ApiSubscriptionDatabaseTest {
    Connection connection;
    JdbcTemplate jdbc;
    ApiSubscriptionService subscriptions;
    ApiSubscriberDashboardService dashboard;

    @BeforeEach void prepare() throws Exception {
        connection=DriverManager.getConnection(
                System.getenv().getOrDefault("SPRING_DATASOURCE_URL","jdbc:postgresql://localhost:5433/doors_registry"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME","doorsuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD",""));
        connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("CREATE TEMP TABLE external_api_clients(client_id BIGINT PRIMARY KEY, client_name TEXT, is_active BOOLEAN)");
        jdbc.execute("CREATE TEMP TABLE agents(agent_id VARCHAR(255) PRIMARY KEY, display_name TEXT)");
        jdbc.execute("CREATE TEMP TABLE users(user_id INTEGER PRIMARY KEY, username TEXT, role TEXT)");
        jdbc.execute("CREATE TEMP TABLE user_api_clients(user_id INTEGER, client_id BIGINT, PRIMARY KEY(user_id,client_id))");
        jdbc.execute("CREATE TEMP TABLE user_authorized_agents(user_name TEXT, agent_id VARCHAR(255))");
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V2026091701__api_subscriptions.sql"))
                .replace("CREATE TABLE ","CREATE TEMP TABLE ");
        jdbc.execute(sql);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V2026091702__api_user_licenses_and_access_calendar.sql"))
                .replace("CREATE TABLE ","CREATE TEMP TABLE "));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V2026091703__weekly_api_access.sql"))
                .replace("CREATE TABLE ","CREATE TEMP TABLE "));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V2026091704__subscription_periods.sql"))
                .replace("CREATE TABLE ","CREATE TEMP TABLE "));
        jdbc.update("INSERT INTO external_api_clients VALUES (1,'Client A',true),(2,'Client B',true),(3,'Client C',true)");
        jdbc.update("INSERT INTO agents VALUES ('Dev-01','GePNIC test instance'),('Dev-02','Second instance')");
        jdbc.update("INSERT INTO users VALUES (1,'alice','ApiUser'),(2,'bob','ApiUser'),(3,'shared','ApiUser')");
        jdbc.update("INSERT INTO user_api_clients VALUES (1,1),(2,2),(3,1)");
        jdbc.update("INSERT INTO user_authorized_agents VALUES ('Client A','Dev-01'),('Client B','Dev-02'),('Client C','Dev-01')");
        subscriptions=new ApiSubscriptionService(jdbc,new ObjectMapper().findAndRegisterModules());
        dashboard=new ApiSubscriberDashboardService(jdbc,subscriptions);
    }
    @AfterEach void rollback() throws Exception { if(connection!=null) { connection.rollback(); connection.close(); } }
    ApiLicense value(boolean enabled,long version) {
        return new ApiLicense("Dev-01",LocalDate.now().minusDays(1),LocalDate.now().plusDays(30),
                enabled,new BigDecimal("100.50"),new BigDecimal("25.25"),BigDecimal.TEN,BigDecimal.ZERO,"Test",version);
    }
    @Test void migrationSaveHistoryAndStaleWriteProtection() {
        var saved=subscriptions.save(1,value(false,0),"manager");
        assertEquals(1,saved.version());assertEquals(new BigDecimal("100.50"),saved.gepnicDue());
        assertThrows(DoorsApiException.class,()->subscriptions.enforce(1));
        assertThrows(DoorsApiException.class,()->subscriptions.enforce(3)); // A different key, same Agent, no direct user assignment.
        assertDoesNotThrow(()->subscriptions.enforce(2)); // Different Agent.
        assertEquals(2,subscriptions.clients("alice").size());
        assertEquals(2,subscriptions.history(1).size());
        assertThrows(DoorsApiException.class,()->subscriptions.save(1,value(true,0),"manager"));
        assertFalse(subscriptions.license(1).serviceEnabled());
        subscriptions.save(1,value(true,1),"manager");
        assertDoesNotThrow(()->subscriptions.enforce(1));
        assertEquals(3,subscriptions.history(1).size());
    }
    @Test void usageScopesByAssignedClientAndTotalsDoNotDuplicateSharedKeys() {
        jdbc.update("""
                INSERT INTO api_egress_events(client_id,endpoint,status_code,response_bytes,record_count,duration_ms,transfer_complete,occurred_at)
                VALUES (1,'/api/test',200,1024,3,10,true,'2026-09-17T08:00:00Z'),
                  (2,'/api/test',403,128,0,4,true,'2026-09-17T08:00:00Z'),
                  (1,'/api/test',200,256,0,20,false,'2026-09-17T08:00:00Z'),
                  (1,'/api/test',200,4096,1,10,true,'2026-09-17T18:30:00Z')
                """);
        var day=LocalDate.parse("2026-09-17");
        var own=dashboard.dashboard("alice",day,day);
        var totals=(Map<?,?>)own.get("totals");
        assertEquals(1280L,((Number)totals.get("responseBytes")).longValue());
        assertEquals(1L,((Number)totals.get("failed")).longValue());
        assertEquals(1,((List<?>)own.get("clients")).size());
        var all=(Map<?,?>)dashboard.dashboard(null,day,day).get("totals");
        assertEquals(1408L,((Number)all.get("responseBytes")).longValue());
        assertEquals(3L,((Number)all.get("requests")).longValue());
        var empty=(Map<?,?>)dashboard.dashboard("unassigned",day,day).get("totals");
        assertEquals(0L,((Number)empty.get("requests")).longValue());
    }
    @Test void notificationsReadStateIsPerSubscriberAndForeignNoticeDenied() {
        assertEquals(1,dashboard.publish(1L,"Maintenance","Test notice","INFO",OffsetDateTime.now().plusDays(1),"manager"));
        long id=((Number)dashboard.notices("alice").getFirst().get("noticeId")).longValue();
        assertTrue(dashboard.notices("bob").isEmpty());
        assertThrows(SecurityException.class,()->dashboard.markRead(id,"bob"));
        dashboard.markRead(id,"alice");
        assertEquals(true,dashboard.notices("alice").getFirst().get("isRead"));
        assertEquals(false,dashboard.notices("shared").getFirst().get("isRead"));
        assertEquals(3,dashboard.publish(null,"All","Broadcast","WARNING",OffsetDateTime.now().plusDays(1),"manager"));
    }
    @Test void emptyCalendarIsUnrestrictedAndExplicitCalendarIsEnforced() {
        assertDoesNotThrow(()->subscriptions.enforce(1));
        var now=OffsetDateTime.now();
        subscriptions.saveSchedule(1,new ApiAccessSchedule(0,List.of(new ApiAccessSchedule.Window(now.plusDays(1),now.plusDays(2)))),"manager");
        assertEquals("DOORS-ACCESS-WINDOW-CLOSED",assertThrows(DoorsApiException.class,()->subscriptions.enforce(1)).getCode());
        assertThrows(DoorsApiException.class,()->subscriptions.saveSchedule(1,new ApiAccessSchedule(0,List.of()),"manager"));
        subscriptions.saveSchedule(1,new ApiAccessSchedule(1,List.of()),"manager");
        assertDoesNotThrow(()->subscriptions.enforce(1));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM api_client_schedule_history WHERE client_id=1",Integer.class));
    }
    @Test void invalidDateRangesAreRejectedBeforeQueries() {
        assertThrows(IllegalArgumentException.class,()->dashboard.dashboard("alice",LocalDate.now(),LocalDate.now().minusDays(1)));
        assertThrows(IllegalArgumentException.class,()->dashboard.dashboard("alice",LocalDate.now(),LocalDate.now().plusDays(367)));
    }
    @Test void weeklyAccessCoversAllAgentKeysAndRecordsHistoryWithStaleWriteProtection() {
        assertThrows(IllegalArgumentException.class, () -> subscriptions.saveWeeklyAccess(1,new ApiWeeklyAccess(0,true,List.of()),"manager"));
        subscriptions.save(1,value(true,0),"manager");
        subscriptions.saveWeeklyAccess(1,new ApiWeeklyAccess(0,true,List.of()),"manager");
        assertEquals("DOORS-WEEKLY-ACCESS-CLOSED",assertThrows(DoorsApiException.class,()->subscriptions.enforce(1)).getCode());
        assertThrows(DoorsApiException.class,()->subscriptions.enforce(3));
        assertDoesNotThrow(()->subscriptions.enforce(2));
        assertThrows(DoorsApiException.class,()->subscriptions.saveWeeklyAccess(1,new ApiWeeklyAccess(0,false,List.of()),"manager"));
        subscriptions.saveWeeklyAccess(1,new ApiWeeklyAccess(1,false,List.of()),"manager");
        assertDoesNotThrow(()->subscriptions.enforce(3));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM api_user_weekly_access_history WHERE user_id=1",Integer.class));
        subscriptions.save(1,value(false,1),"manager");
        assertEquals("DOORS-SERVICE-DISABLED",assertThrows(DoorsApiException.class,()->subscriptions.enforce(1)).getCode());
    }
    @Test void consolidatedUserUsageContainsOnlyExactAgentClientsForDrilldown() {
        subscriptions.save(1,value(true,0),"manager");
        jdbc.update("""
                INSERT INTO api_egress_events(client_id,endpoint,status_code,response_bytes,record_count,duration_ms,transfer_complete,occurred_at)
                VALUES (1,'/api/test',200,100,1,10,true,'2026-09-17T08:00:00Z'),
                       (3,'/api/test',200,250,2,20,true,'2026-09-17T08:00:00Z'),
                       (2,'/api/test',200,900,3,30,true,'2026-09-17T08:00:00Z')
                """);
        var result=dashboard.dashboard("alice",LocalDate.parse("2026-09-17"),LocalDate.parse("2026-09-17"));
        var users=(List<Map<String,Object>>)result.get("subscribers");
        assertEquals(1,users.size());
        var user=users.getFirst();
        assertEquals("Dev-01",user.get("agentId"));
        assertEquals(350,((Number)user.get("responseBytes")).intValue());
        var clients=(List<Map<String,Object>>)user.get("clients");
        assertEquals(Set.of(1L,3L),clients.stream().map(c->((Number)c.get("clientId")).longValue()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(350,clients.stream().mapToInt(c->((Number)((Map<?,?>)c.get("usage")).get("responseBytes")).intValue()).sum());
    }
    @Test void renewalPreservesCurrentPeriodAndTotalsAllUnpaidBalancesWithoutOffsettingCredits() {
        subscriptions.save(1,value(true,0),"manager");
        var today=LocalDate.now();
        var renewal=new ApiSubscriptionPeriod(today.plusDays(31),today.plusDays(365),new BigDecimal("200.00"),new BigDecimal("20.00"),
                new BigDecimal("50.00"),new BigDecimal("60.00"),"Future renewal",0);
        var saved=subscriptions.savePeriod(1,null,renewal,"manager");
        assertEquals(2,subscriptions.periods(1).size());
        assertEquals("UPCOMING",saved.get("status"));
        assertEquals(new BigDecimal("100.50"),subscriptions.license(1).gepnicDue());
        var totals=subscriptions.accountingTotals(1);
        assertEquals(new BigDecimal("255.25"),totals.get("gepnicOutstanding"));
        assertEquals(new BigDecimal("10.00"),totals.get("doorsOutstanding"));
        assertEquals(new BigDecimal("10.00"),totals.get("doorsCredit"));
        assertDoesNotThrow(()->subscriptions.enforce(1));
        subscriptions.saveAccount(1,new ApiSubscriptionAccount("Dev-01",false,1),"manager");
        assertEquals(2,subscriptions.periods(1).size());
        assertEquals(new BigDecimal("255.25"),subscriptions.accountingTotals(1).get("gepnicOutstanding"));
        assertEquals("DOORS-SERVICE-DISABLED",assertThrows(DoorsApiException.class,()->subscriptions.enforce(3)).getCode());
        var account=subscriptions.licenses("alice").getFirst();
        assertEquals("CURRENT",((Map<?,?>)account.get("currentPeriod")).get("status"));
        assertEquals(saved.get("periodId"),((Map<?,?>)account.get("nextPeriod")).get("periodId"));
    }
    @Test void periodEditsAreScopedVersionedAndCannotOverlapAnotherPeriod() {
        subscriptions.saveAccount(1,new ApiSubscriptionAccount("Dev-01",true,0),"manager");
        var original=new ApiSubscriptionPeriod(LocalDate.of(2025,1,1),LocalDate.of(2025,12,31),BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"First",0);
        var p=subscriptions.savePeriod(1,null,original,"manager"); long id=((Number)p.get("periodId")).longValue();
        assertThrows(IllegalArgumentException.class,()->subscriptions.savePeriod(1,null,original,"manager"));
        var paid=new ApiSubscriptionPeriod(original.validFrom(),original.validTo(),BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,"Paid",1);
        subscriptions.savePeriod(1,id,paid,"manager");
        assertThrows(DoorsApiException.class,()->subscriptions.savePeriod(1,id,paid,"manager"));
        subscriptions.saveAccount(2,new ApiSubscriptionAccount("Dev-02",true,0),"manager");
        assertThrows(NoSuchElementException.class,()->subscriptions.savePeriod(2,id,paid,"manager"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM api_subscription_period_history WHERE period_id=?",Integer.class,id));
        assertEquals(1,subscriptions.periods(1).size());
        assertEquals("Paid",subscriptions.periods(1).getFirst().get("notes"));
        assertNull(subscriptions.licenses("alice").getFirst().get("currentPeriod"));
        assertDoesNotThrow(()->subscriptions.enforce(1)); // An ended period is advisory.
    }
}
