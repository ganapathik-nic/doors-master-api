package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiLicensePolicyTest {
    private ApiLicense license(boolean enabled) {
        return new ApiLicense("agent",LocalDate.parse("2026-09-17"),LocalDate.parse("2026-09-18"),
                enabled,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.TEN,BigDecimal.ZERO,"",0);
    }
    @Test void licenseExpiryFutureStartAndOutstandingBalanceNeverAutomaticallySuspendService() {
        for(String instant:List.of("2026-09-16T00:00:00Z","2026-09-17T00:00:00Z","2026-12-01T00:00:00Z"))
            assertTrue(ApiLicensePolicy.evaluate(license(true),Instant.parse(instant)).allowed());
    }
    @Test void manualSuspensionAlwaysDenies() {
        assertEquals("DOORS-SERVICE-DISABLED",ApiLicensePolicy.evaluate(license(false),Instant.now()).code());
    }
    @Test void emptyCalendarMeansUnrestrictedTimeAccess() {
        assertTrue(ApiLicensePolicy.schedule(new ApiAccessSchedule(0,List.of()),Instant.now()).allowed());
    }
    @Test void calendarHonorsDateTimeOffsetAndDisjointWindows() {
        var schedule=new ApiAccessSchedule(0,List.of(
                new ApiAccessSchedule.Window(OffsetDateTime.parse("2026-09-17T22:00:00+05:30"),OffsetDateTime.parse("2026-09-18T06:00:00+05:30")),
                new ApiAccessSchedule.Window(OffsetDateTime.parse("2026-09-20T09:00:00+05:30"),OffsetDateTime.parse("2026-09-20T17:00:00+05:30"))));
        assertFalse(ApiLicensePolicy.schedule(schedule,Instant.parse("2026-09-17T16:29:59Z")).allowed());
        assertTrue(ApiLicensePolicy.schedule(schedule,Instant.parse("2026-09-17T16:30:00Z")).allowed());
        assertTrue(ApiLicensePolicy.schedule(schedule,Instant.parse("2026-09-17T23:59:59Z")).allowed());
        assertFalse(ApiLicensePolicy.schedule(schedule,Instant.parse("2026-09-18T00:30:00Z")).allowed());
        assertTrue(ApiLicensePolicy.schedule(schedule,Instant.parse("2026-09-20T03:30:00Z")).allowed());
    }
    @Test void invalidCalendarPeriodsRejected() {
        var now=OffsetDateTime.now();
        assertThrows(IllegalArgumentException.class,()->ApiLicensePolicy.validate(new ApiAccessSchedule(0,List.of(new ApiAccessSchedule.Window(now,now)))));
    }
    @Test void existingUnconfiguredUsersAreExplicitlyIdentified() {
        assertEquals("UNCONFIGURED",ApiLicensePolicy.evaluate(null,Instant.now()).code());
    }
}
