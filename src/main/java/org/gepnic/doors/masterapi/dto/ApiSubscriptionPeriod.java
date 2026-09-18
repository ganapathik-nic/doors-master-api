package org.gepnic.doors.masterapi.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ApiSubscriptionPeriod(@NotNull LocalDate validFrom, @NotNull LocalDate validTo,
        @NotNull @DecimalMin("0") @Digits(integer=13, fraction=2) BigDecimal gepnicDue,
        @NotNull @DecimalMin("0") @Digits(integer=13, fraction=2) BigDecimal gepnicPaid,
        @NotNull @DecimalMin("0") @Digits(integer=13, fraction=2) BigDecimal doorsDue,
        @NotNull @DecimalMin("0") @Digits(integer=13, fraction=2) BigDecimal doorsPaid,
        @NotNull @Size(max=2000) String notes, @Min(0) long version) {}
