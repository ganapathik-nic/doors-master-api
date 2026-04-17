package org.gepnic.doors.masterapi.dto;

/**
 * 🛡️ Updated Record to include Captcha fields for Security Audit compliance.
 */
public record LoginRequest(
    String username, 
    String password, 
    String captchaId, 
    String captchaValue
) {}