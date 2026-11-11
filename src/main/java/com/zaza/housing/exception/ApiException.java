package com.zaza.housing.exception;

import org.springframework.http.HttpStatus;
import com.zaza.housing.enums.ApiErrorCode;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiErrorCode code;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.code = ApiErrorCode.fromStatus(status.value());
    }

    public ApiException(ApiErrorCode code) { this(code, code.detail()); }

    public ApiException(ApiErrorCode code, String message) {
        super(message);
        this.status = code.status();
        this.code = code;
    }

    public ApiException(ApiErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.status = code.status();
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public ApiErrorCode code() { return code; }
}
