package com.zaza.housing.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTests {
    @Test
    void excessAttemptsAreBlockedAndANewWindowAllowsRequestsAgain() throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var filter = new AuthRateLimitFilter(clock, 2);
        assertThat(attempt(filter)).isEqualTo(204);
        assertThat(attempt(filter)).isEqualTo(204);
        assertThat(attempt(filter)).isEqualTo(429);
        now.set(now.get().plusSeconds(60));
        assertThat(attempt(filter)).isEqualTo(204);
    }

    private int attempt(AuthRateLimitFilter filter) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }
}
