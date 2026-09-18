package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.ApiLicense;
import org.gepnic.doors.masterapi.dto.ApiAccessSchedule;
import java.time.*;

/** License dates/payment balances are advisory; manual suspension and calendars restrict APIs. */
public final class ApiLicensePolicy {
    private ApiLicensePolicy() {}
    public record Decision(String code, String message, boolean allowed) {}

    public static Decision evaluate(ApiLicense license, Instant now) {
        if (license == null) return new Decision("UNCONFIGURED", "License has not been configured; existing API access applies.", true);
        if (!Boolean.TRUE.equals(license.serviceEnabled()))
            return new Decision("DOORS-SERVICE-DISABLED", "DOORS API service is disabled by the DataManager. Contact the DataManager about your subscription.", false);
        return new Decision("ACTIVE", "DOORS API service is enabled. License dates and payment balances do not automatically suspend service.", true);
    }
    public static Decision schedule(ApiAccessSchedule schedule, Instant now) {
        if (schedule.windows().isEmpty()) return new Decision("ACTIVE", "No calendar restriction: API access is available at any time.", true);
        boolean allowed = schedule.windows().stream().anyMatch(w -> !now.isBefore(w.from().toInstant()) && now.isBefore(w.to().toInstant()));
        return allowed ? new Decision("ACTIVE", "API access is within an allowed calendar period.", true)
                : new Decision("DOORS-ACCESS-WINDOW-CLOSED", "API access is outside the configured date-and-time periods. Contact the DataManager for the access calendar.", false);
    }
    public static void validate(ApiLicense license) {
        if (license.validTo().isBefore(license.validFrom())) throw new IllegalArgumentException("License end date must not precede start date.");
    }
    public static void validate(ApiAccessSchedule schedule) {
        for (var window : schedule.windows()) {
            if (!window.to().isAfter(window.from())) throw new IllegalArgumentException("Each calendar period must end after it starts.");
        }
    }
}
