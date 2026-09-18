package org.gepnic.doors.masterapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public record ApiAccessSchedule(@Min(0) long version, @NotNull @Size(max=200) List<@NotNull @Valid Window> windows) {
    public record Window(@NotNull OffsetDateTime from, @NotNull OffsetDateTime to) {}
}
