package org.gepnic.doors.masterapi.dto;
import jakarta.validation.constraints.*;
public record CategoryRequest(@Null Long id, @NotBlank @Size(max=20) String code,
        @NotBlank @Size(max=100) String name, @Positive Long parentId) {}
