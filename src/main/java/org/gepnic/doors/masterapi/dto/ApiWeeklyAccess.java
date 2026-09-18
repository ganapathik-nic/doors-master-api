package org.gepnic.doors.masterapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Sunday=0 through Saturday=6. Times are HH:mm in Asia/Kolkata. */
public record ApiWeeklyAccess(@Min(0) long version, @NotNull Boolean restricted,
        @NotNull @Size(max=100) List<@NotNull @Valid Slot> slots) {
    public record Slot(@Min(0) @Max(6) int startDay,
                       @NotNull @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
                       @Min(0) @Max(6) int endDay,
                       @NotNull @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String endTime) {}
}
