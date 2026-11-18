package com.zaza.housing.filter;

import com.zaza.housing.security.SecurityErrors;
import com.zaza.housing.util.BoundedRateLimiter;
import com.zaza.housing.util.NetworkPeer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {
  private final BoundedRateLimiter requests;

  public AuthRateLimitFilter(Clock clock, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Auth request limit must be positive.");
    this.requests = new BoundedRateLimiter(clock, limit, 10000);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean discoveryEndpoint =
        java.util.Set.of("POST", "PUT", "DELETE").contains(request.getMethod())
            && request.getServletPath().startsWith("/api/v1/me/discovery/");
    boolean protectedEndpoint =
        discoveryEndpoint
            || "POST".equals(request.getMethod())
                && (request.getServletPath().startsWith("/api/v1/me/support/")
                    || request.getServletPath().startsWith("/api/v1/auth/")
                    || request.getServletPath().startsWith("/api/v1/me/security/")
                    || request.getServletPath().startsWith("/api/v1/admin/operations/")
                    || request.getServletPath().startsWith("/api/v1/admin/verifications/")
                    || request
                        .getServletPath()
                        .matches("/api/v1/(me|organizations/[^/]+)/verifications/[^/]+/submit")
                    || request
                        .getServletPath()
                        .matches("/api/v1/(me|organizations/[^/]+)/documents/[^/]+/purge")
                    || request
                        .getServletPath()
                        .matches(
                            "/api/v1/(me|organizations/[^/]+/properties/[^/]+)/leases/[^/]+/inspections/[^/]+/(share|accept)")
                    || request
                        .getServletPath()
                        .matches(
                            "/api/v1/organizations/[^/]+/caretaker/assignments(/[^/]+/(accept|decline|revoke))?")
                    || request.getServletPath().startsWith("/api/v1/admin/conversation-reports/")
                    || request.getServletPath().startsWith("/api/v1/admin/service-finance/")
                    || request.getServletPath().matches("/api/v1/organizations/[^/]+/(members|invitations)(/[^/]+/(resend|cancel))?")
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
    if (protectedEndpoint && !requests.allow(NetworkPeer.bucket(request.getRemoteAddr()))) {
      response.setHeader("Retry-After", "60");
      SecurityErrors.write(response, 429);
      return;
    }
    chain.doFilter(request, response);
  }

}
