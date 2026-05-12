package org.gepnic.doors.masterapi.dto;

import lombok.*;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // 🚀 This hides any "null" fields
@JsonIgnoreProperties(ignoreUnknown = true) // 🛡️ This stops Jackson from guessing fields
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private String timestamp;
    private int statusCode;

    // --- SUCCESS FACTORY ---
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .statusCode(200)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }

    // --- ERROR FACTORY (Single Arg) ---
    public static <T> ApiResponse<T> error(String message) {
        return error(message, 500);
    }

    // --- ERROR FACTORY (Two Args) ---
    public static <T> ApiResponse<T> error(String message, int code) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .data(null)
                .statusCode(code)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }
}