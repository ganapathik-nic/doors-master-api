package org.gepnic.doors.masterapi.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class DoorsApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String problemType;
    private final String title;
    private final boolean retryable;
    private final Map<String, Object> extensions;

    public DoorsApiException(
            HttpStatus status,
            String code,
            String problemType,
            String title,
            String detail,
            boolean retryable,
            Map<String, Object> extensions) {
        super(detail);
        this.status = status;
        this.code = code;
        this.problemType = problemType;
        this.title = title;
        this.retryable = retryable;
        this.extensions = extensions == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }
}
