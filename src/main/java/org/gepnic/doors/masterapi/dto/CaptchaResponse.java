package org.gepnic.doors.masterapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CaptchaResponse {
    private String id;    // The key to find the actual value in the cache/session
    private String image; // The Base64 string
}