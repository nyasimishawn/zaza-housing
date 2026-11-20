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

public class MapTileRateLimitFilter extends OncePerRequestFilter {
  private final BoundedRateLimiter requests;

  public MapTileRateLimitFilter(Clock clock, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Tile request limit must be positive.");
    this.requests = new BoundedRateLimiter(clock, limit, 10000);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod())
        && request.getServletPath().startsWith("/api/v1/discovery/tiles/")) {
      if (!requests.allow(NetworkPeer.bucket(request.getRemoteAddr()))) {
        response.setHeader("Retry-After", "60");
        SecurityErrors.write(response, 429);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
