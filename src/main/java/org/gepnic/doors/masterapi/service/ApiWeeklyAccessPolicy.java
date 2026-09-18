package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.ApiWeeklyAccess;
import java.time.*;

public final class ApiWeeklyAccessPolicy {
    private ApiWeeklyAccessPolicy() {}
    private static final int WEEK = 7 * 24 * 60;
    private static int minute(int day, String time) {
        if (day < 0 || day > 6 || time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new IllegalArgumentException("Choose a valid weekday and time (HH:mm).");
        var t = LocalTime.parse(time);
        return day * 1440 + t.getHour() * 60 + t.getMinute();
    }
    public static void validate(ApiWeeklyAccess value) {
        if (value == null || value.restricted() == null || value.slots() == null || value.slots().size() > 100)
            throw new IllegalArgumentException("A weekly access mode and up to 100 time slots are required.");
        boolean[] occupied = new boolean[WEEK];
        for (var slot : value.slots()) {
            if (slot == null) throw new IllegalArgumentException("A time slot cannot be empty.");
            int from = minute(slot.startDay(), slot.startTime()), to = minute(slot.endDay(), slot.endTime());
            if (from == to) throw new IllegalArgumentException("A slot must end after its start; for a full day use 00:00 to the next day at 00:00.");
            for (int cursor = from; cursor != to; cursor = (cursor + 1) % WEEK) {
                if (occupied[cursor]) throw new IllegalArgumentException("Weekly time slots overlap. Combine or adjust them before saving.");
                occupied[cursor] = true;
            }
        }
    }
    public static ApiLicensePolicy.Decision evaluate(ApiWeeklyAccess value, Instant now) {
        if (!Boolean.TRUE.equals(value.restricted()))
            return new ApiLicensePolicy.Decision("ACTIVE", "Weekly access is unrestricted (24 × 7).", true);
        var local = now.atZone(ZoneId.of("Asia/Kolkata"));
        int current = (local.getDayOfWeek().getValue() % 7) * 1440 + local.getHour() * 60 + local.getMinute();
        boolean allowed = value.slots().stream().anyMatch(slot -> {
            int from = minute(slot.startDay(), slot.startTime()), to = minute(slot.endDay(), slot.endTime());
            return from < to ? current >= from && current < to : current >= from || current < to;
        });
        return allowed ? new ApiLicensePolicy.Decision("ACTIVE", "Within the allowed weekly API access hours (India time).", true)
                : new ApiLicensePolicy.Decision("DOORS-WEEKLY-ACCESS-CLOSED", "API access is outside the subscriber's weekly time slots (Asia/Kolkata). Check the dashboard or contact the DataManager.", false);
    }
}
