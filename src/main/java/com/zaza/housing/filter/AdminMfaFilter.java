package com.zaza.housing.filter;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AccountSecurityRepository;
import com.zaza.housing.util.TokenUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminMfaFilter extends OncePerRequestFilter {
  private final AccountSecurityRepository repository;
  private final Clock clock;
  private final boolean required;

  public AdminMfaFilter(AccountSecurityRepository repository, Clock clock, boolean required) {
    this.repository = repository;
    this.clock = clock;
    this.required = required;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (required && request.getServletPath().startsWith("/api/v1/admin/")) {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null
          && auth.getPrincipal() instanceof AuthenticatedUser a
          && "PLATFORM_ADMIN".equals(a.platformRole())) {
        String h = request.getHeader("Authorization");
        if (h == null
            || !repository.adminVerified(
                a.id(), TokenUtils.hashToken(h.substring(7)), clock.instant())) {
          response.setStatus(403);
          response.setContentType("application/problem+json");
          response.setHeader("Cache-Control", "no-store");
          response
              .getWriter()
              .write(
                  "{\"status\":403,\"detail\":\"Verify your email and multi-factor authentication"
                      + " in Account security before using administration.\"}");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
