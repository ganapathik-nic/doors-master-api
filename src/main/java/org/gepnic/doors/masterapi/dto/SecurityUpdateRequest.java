package org.gepnic.doors.masterapi.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for Ver 1.0.1 IP Whitelisting updates.
 * Captures the comma-separated IP string from the Vue frontend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecurityUpdateRequest {
    
    private String ipWhitelist;
    
}