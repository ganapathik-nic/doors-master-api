package org.gepnic.doors.masterapi.dto;

import jakarta.validation.constraints.*;

public record ApiSubscriptionAccount(@NotBlank @Size(max=255) String agentId,
        @NotNull Boolean serviceEnabled, @Min(0) long version) {}
