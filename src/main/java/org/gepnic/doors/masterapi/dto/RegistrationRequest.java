package org.gepnic.doors.masterapi.dto;

/**
 * Data Transfer Object (DTO) for User Registration.
 * Using a Java Record (introduced in Java 14+) for conciseness.
 */
public record RegistrationRequest(
    String name,
    String email,
    String org,
    String role,
    String description
) {}
