package com.zaza.housing.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityErrors {
    private SecurityErrors() { }

    public static void write(HttpServletResponse response, int status) throws IOException {
        String title = switch (status) {
            case 403 -> "Forbidden";
            case 429 -> "Too Many Requests";
            default -> "Unauthorized";
        };
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + "}");
    }
}
