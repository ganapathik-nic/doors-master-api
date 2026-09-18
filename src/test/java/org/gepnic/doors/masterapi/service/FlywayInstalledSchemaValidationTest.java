package org.gepnic.doors.masterapi.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opted-in, read-only validation; never migrates, repairs or cleans a database. */
@EnabledIfEnvironmentVariable(named="DOORS_FLYWAY_VALIDATE", matches="true")
class FlywayInstalledSchemaValidationTest {
    @Test void installedMigrationsMatchRepository() {
        var flyway = Flyway.configure()
            .dataSource(System.getenv("SPRING_DATASOURCE_URL"), System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"))
            .locations("classpath:db/migration").cleanDisabled(true).load();
        flyway.validate();
        assertEquals(0, flyway.info().pending().length, "Local database must have all repository migrations applied");
        assertNotNull(flyway.info().current());
        System.out.println("Validated installed Flyway migrations: " + flyway.info().applied().length + "; latest version: " + flyway.info().current().getVersion());
    }
}
