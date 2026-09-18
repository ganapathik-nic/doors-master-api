package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class ApiSubscriptionMigrationTest {
    static String migration(String name) throws Exception {
        return Files.readString(Path.of("src/main/resources/db/migration/" + name));
    }
    @Test void originalMigrationMatchesAlreadyInstalledChecksum() throws Exception {
        var crc = new CRC32();
        migration("V2026091701__api_subscriptions.sql").lines()
                .forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1757144984, (int) crc.getValue());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DOORS_SUBSCRIPTION_DB_TESTS", matches="true")
    void existingAccountingAndServiceStateSurvivePeriodMigration() throws Exception {
        try (var connection = DriverManager.getConnection(
                System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5433/doors_registry"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "doorsuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            try (var statement=connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE users(user_id INTEGER PRIMARY KEY); CREATE TEMP TABLE agents(agent_id VARCHAR(255) PRIMARY KEY); CREATE TEMP TABLE external_api_clients(client_id BIGINT PRIMARY KEY)");
                for (String file : new String[]{"V2026091701__api_subscriptions.sql", "V2026091702__api_user_licenses_and_access_calendar.sql"})
                    statement.execute(migration(file).replace("CREATE TABLE ", "CREATE TEMP TABLE "));
                statement.execute("INSERT INTO users VALUES (1); INSERT INTO agents VALUES ('Agent-1')");
                statement.execute("""
                        INSERT INTO api_user_licenses(user_id,agent_id,valid_from,valid_to,service_enabled,gepnic_due,gepnic_paid,doors_due,doors_paid,notes,version,updated_by)
                        VALUES (1,'Agent-1','2026-04-01','2027-03-31',false,1000.25,750.50,200,225,'Existing accounting',7,'manager');
                        INSERT INTO api_license_history(user_id,version,snapshot,changed_by) VALUES (1,7,'{"legacy":true}','manager');
                        """);
                statement.execute(migration("V2026091704__subscription_periods.sql").replace("CREATE TABLE ","CREATE TEMP TABLE "));
                try(var rows=statement.executeQuery("SELECT p.*,a.agent_id,a.service_enabled,a.version AS account_version FROM api_subscription_periods p JOIN api_user_licenses a USING(user_id)")) {
                    assertTrue(rows.next());
                    assertEquals("2026-04-01",rows.getString("valid_from"));
                    assertEquals("2027-03-31",rows.getString("valid_to"));
                    assertEquals(new java.math.BigDecimal("1000.25"),rows.getBigDecimal("gepnic_due"));
                    assertEquals(new java.math.BigDecimal("750.50"),rows.getBigDecimal("gepnic_paid"));
                    assertEquals(new java.math.BigDecimal("225.00"),rows.getBigDecimal("doors_paid"));
                    assertEquals("Existing accounting",rows.getString("notes"));
                    assertEquals("Agent-1",rows.getString("agent_id"));
                    assertFalse(rows.getBoolean("service_enabled"));
                    assertEquals(7,rows.getLong("account_version"));
                    assertFalse(rows.next());
                }
                for(String table:new String[]{"api_license_history","api_subscription_period_history"}) {
                    try(var rows=statement.executeQuery("SELECT count(*) FROM "+table)) { assertTrue(rows.next());assertEquals(1,rows.getInt(1)); }
                }
            } finally { connection.rollback(); }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DOORS_SUBSCRIPTION_DB_TESTS", matches="true")
    void upgradeRefusesToDiscardExistingClientLicensesOrHistory() throws Exception {
        try (var connection = DriverManager.getConnection(
                System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5433/doors_registry"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "doorsuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // Session-local sentinels shadow real tables; no application data is touched.
                statement.execute("CREATE TEMP TABLE api_client_licenses(id INTEGER)");
                statement.execute("CREATE TEMP TABLE api_license_history(id INTEGER)");
                for (String table : new String[]{"api_client_licenses", "api_license_history"}) {
                    statement.executeUpdate("INSERT INTO " + table + " VALUES (1)");
                    var savepoint = connection.setSavepoint();
                    var error = assertThrows(SQLException.class, () -> statement.execute(
                            migration("V2026091702__api_user_licenses_and_access_calendar.sql")));
                    assertTrue(error.getMessage().contains("reconcile them to API users"));
                    connection.rollback(savepoint);
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                    statement.executeUpdate("DELETE FROM " + table);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
