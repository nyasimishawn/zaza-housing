package com.zaza.housing.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.exception.ApiErrors;

public final class SecurityErrors {
    private SecurityErrors() { }

    public static void write(HttpServletResponse response, int status) throws IOException {
        ApiErrors.write(response, ApiErrorCode.fromStatus(status));
    }
}
