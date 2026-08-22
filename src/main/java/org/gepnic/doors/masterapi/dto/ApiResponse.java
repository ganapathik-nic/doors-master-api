package org.gepnic.doors.masterapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private String status;   // "SUCCESS" or "ERROR"
    private String message;  // "DOORS-Data Orchestration Completed"
    private T payload;       // 👈 Renamed from 'data' to 'payload'

    public static <T> ApiResponse<T> success(T payload, String message) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message(message != null ? message : "DOORS-Data Orchestration Completed")
                .payload(payload)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(String message, int code) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .build();
    }
}