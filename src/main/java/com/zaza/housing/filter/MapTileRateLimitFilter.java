package com.zaza.housing.filter;

import com.zaza.housing.security.SecurityErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

public class MapTileRateLimitFilter extends OncePerRequestFilter {
  private final Clock clock;
  private final int limit;
  private final Map<String, Integer> requests = new HashMap<>();
  private long minute = -1;

  public MapTileRateLimitFilter(Clock clock, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Tile request limit must be positive.");
    this.clock = clock;
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod())
        && request.getServletPath().startsWith("/api/v1/discovery/tiles/")) {
      boolean allowed;
      synchronized (requests) {
        long current = clock.instant().getEpochSecond() / 60;
        if (minute != current) { requests.clear(); minute = current; }
        String address = request.getRemoteAddr();
        int count = requests.getOrDefault(address, 0);
        allowed = count < limit && (requests.containsKey(address) || requests.size() < 10000);
        if (allowed) requests.put(address, count + 1);
      }
      if (!allowed) {
        response.setHeader("Retry-After", "60");
        SecurityErrors.write(response, 429);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
