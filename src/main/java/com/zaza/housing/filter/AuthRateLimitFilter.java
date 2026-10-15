package com.zaza.housing.filter;

import com.zaza.housing.security.SecurityErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int limit;

    public AuthRateLimitFilter(Clock clock, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Auth request limit must be positive.");
        this.clock = clock;
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
    boolean protectedEndpoint =
        "POST".equals(request.getMethod())
            && (request.getServletPath().startsWith("/api/v1/admin/service-finance/")
                || "/api/v1/auth/login".equals(request.getServletPath())
                || "/api/v1/auth/register".equals(request.getServletPath())
                || request
                    .getServletPath()
                    .matches(
                        "/api/v1/(me|organizations/[^/]+/properties/[^/]+)/leases/[^/]+/(sign|terminations|terminations/[^/]+/accept)")
                || request
                    .getServletPath()
                    .matches(
                        "/api/v1/organizations/[^/]+/properties/[^/]+/leases/[^/]+/billing/(payments|payments/[^/]+/(void|refunds)|claims/[^/]+/(verify|reject)|deposit/refunds)"));
        if (protectedEndpoint) {
            long minute = clock.instant().getEpochSecond() / 60;
            windows.entrySet().removeIf(entry -> entry.getValue().minute < minute);
            String address = request.getRemoteAddr();
            if (windows.size() >= 10000 && !windows.containsKey(address)) {
                response.setHeader("Retry-After", "60");
                SecurityErrors.write(response, 429);
                return;
            }
            Window window = windows.compute(address, (key, existing) ->
                    existing == null || existing.minute != minute ? new Window(minute) : existing);
            if (window.requests.incrementAndGet() > limit) {
                response.setHeader("Retry-After", "60");
                SecurityErrors.write(response, 429);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        final long minute;
        final AtomicInteger requests = new AtomicInteger();
        Window(long minute) { this.minute = minute; }
    }
}
