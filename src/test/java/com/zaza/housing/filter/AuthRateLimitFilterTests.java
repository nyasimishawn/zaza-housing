package com.zaza.housing.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
        return attempt(filter,"/api/v1/auth/login");
    }

    @Test
    void passwordConfirmationEndpointsShareTheAuthenticationRateLimit() throws Exception {
    for (String path :
        java.util.List.of(
            "/api/v1/me/leases/a/sign",
            "/api/v1/organizations/o/properties/p/leases/a/sign",
            "/api/v1/me/leases/a/terminations",
            "/api/v1/organizations/o/properties/p/leases/a/terminations/t/accept")) {
            var filter=new AuthRateLimitFilter(Clock.systemUTC(),1);
            assertThat(attempt(filter,path)).isEqualTo(204);
            assertThat(attempt(filter,path)).isEqualTo(429);
        }
    }

    @Test
    void documentPreviewsDoNotConsumePasswordAttemptQuota() throws Exception {
        var filter=new AuthRateLimitFilter(Clock.systemUTC(),1);
        assertThat(attempt(filter,"/api/v1/me/leases/a/terminations/preview")).isEqualTo(204);
        assertThat(attempt(filter,"/api/v1/me/leases/a/terminations/preview")).isEqualTo(204);
    }

    @Test
    void manualPaymentVerificationAndRefundsSharePasswordAttemptLimits() throws Exception {
        String base="/api/v1/organizations/o/properties/p/leases/l/billing/";
    for (String action :
        java.util.List.of(
            "payments",
            "payments/r/void",
            "payments/r/refunds",
            "claims/c/verify",
            "claims/c/reject",
            "deposit/refunds")) {
            var filter=new AuthRateLimitFilter(Clock.systemUTC(),1);
            assertThat(attempt(filter,base+action)).isEqualTo(204);
            assertThat(attempt(filter,base+action)).isEqualTo(429);
        }
    }

  @Test
  void serviceFinancePasswordConfirmationsShareTheAuthenticationQuota() throws Exception {
    var filter = new AuthRateLimitFilter(Clock.systemUTC(), 2);
    assertThat(attempt(filter, "/api/v1/admin/service-finance/accounts/a/receipts")).isEqualTo(204);
    assertThat(attempt(filter, "/api/v1/admin/service-finance/destinations/d/review"))
        .isEqualTo(204);
    assertThat(attempt(filter, "/api/v1/admin/service-finance/accounts/a/payouts/p/paid"))
        .isEqualTo(429);
  }

  @Test
  void moderationPasswordConfirmationsShareTheAuthenticationQuota() throws Exception {
    var filter = new AuthRateLimitFilter(Clock.systemUTC(), 1);
    assertThat(attempt(filter, "/api/v1/admin/conversation-reports/r/claim")).isEqualTo(204);
    assertThat(attempt(filter, "/api/v1/admin/conversation-reports/r/resolve")).isEqualTo(429);
  }

    private int attempt(AuthRateLimitFilter filter,String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }
}
