package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.ApiWeeklyAccess;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiWeeklyAccessPolicyTest {
    private ApiWeeklyAccess schedule(ApiWeeklyAccess.Slot... slots) { return new ApiWeeklyAccess(0, true, List.of(slots)); }
    private boolean allowed(ApiWeeklyAccess value, String indiaTime) {
        return ApiWeeklyAccessPolicy.evaluate(value, OffsetDateTime.parse(indiaTime + "+05:30").toInstant()).allowed();
    }
    @Test void unrestrictedAndExplicitlyClosedWeekAreDifferent() {
        assertTrue(allowed(new ApiWeeklyAccess(0, false, List.of()), "2026-09-20T12:00:00"));
        assertFalse(allowed(schedule(), "2026-09-20T12:00:00"));
    }
    @Test void overnightSundayToMondayHasExactBoundariesInIndiaTime() {
        var value = schedule(new ApiWeeklyAccess.Slot(0, "22:00", 1, "02:00"));
        ApiWeeklyAccessPolicy.validate(value);
        assertFalse(allowed(value, "2026-09-20T21:59:59"));
        assertTrue(allowed(value, "2026-09-20T22:00:00"));
        assertTrue(allowed(value, "2026-09-21T01:59:59"));
        assertFalse(allowed(value, "2026-09-21T02:00:00"));
        assertFalse(allowed(value, "2026-09-22T01:00:00"));
    }
    @Test void saturdayWrapsIntoSundayAndRepeatsEveryWeek() {
        var value = schedule(new ApiWeeklyAccess.Slot(6, "23:00", 0, "01:00"));
        assertTrue(allowed(value, "2026-09-19T23:00:00"));
        assertTrue(allowed(value, "2026-09-20T00:59:59"));
        assertFalse(allowed(value, "2026-09-20T01:00:00"));
        assertTrue(allowed(value, "2026-09-27T00:30:00"));
    }
    @Test void fullDayAndSeparatedSlotsDoNotAllowTheirGaps() {
        var value = schedule(new ApiWeeklyAccess.Slot(0,"00:00",1,"00:00"),
                new ApiWeeklyAccess.Slot(1,"09:00",1,"12:00"),new ApiWeeklyAccess.Slot(1,"14:00",1,"18:00"));
        ApiWeeklyAccessPolicy.validate(value);
        assertTrue(allowed(value,"2026-09-20T23:59:59"));
        assertFalse(allowed(value,"2026-09-21T00:00:00"));
        assertFalse(allowed(value,"2026-09-21T13:00:00"));
        assertTrue(allowed(value,"2026-09-21T14:00:00"));
    }
    @Test void overlapIncludingAcrossWeekBoundaryAndInvalidTimesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ApiWeeklyAccessPolicy.validate(schedule(
                new ApiWeeklyAccess.Slot(6,"23:00",0,"02:00"),new ApiWeeklyAccess.Slot(0,"01:00",0,"03:00"))));
        for (var slot : List.of(new ApiWeeklyAccess.Slot(0,"09:00",0,"09:00"),
                new ApiWeeklyAccess.Slot(7,"09:00",0,"10:00"),new ApiWeeklyAccess.Slot(0,"24:00",1,"02:00")))
            assertThrows(IllegalArgumentException.class, () -> ApiWeeklyAccessPolicy.validate(schedule(slot)));
        assertDoesNotThrow(() -> ApiWeeklyAccessPolicy.validate(schedule(
                new ApiWeeklyAccess.Slot(1,"09:00",1,"12:00"),new ApiWeeklyAccess.Slot(1,"12:00",1,"18:00"))));
    }
}
