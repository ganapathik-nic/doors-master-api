package org.gepnic.doors.masterapi.dto;

public record MfaVerifyRequest(String challengeId, String code) {}

