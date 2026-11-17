package com.zaza.housing.filter;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class MapTileRateLimitFilterTests {
  @Test
  void tileQuotaIsPerPeerAndDoesNotConsumeAuthenticationQuota() throws Exception {
    var filter = new MapTileRateLimitFilter(Clock.systemUTC(), 2);
    assertThat(request(filter, "198.51.100.1", "/api/v1/discovery/tiles/r/2/1/1.png")).isEqualTo(204);
    assertThat(request(filter, "198.51.100.1", "/api/v1/discovery/tiles/r/2/1/2.png")).isEqualTo(204);
    assertThat(request(filter, "198.51.100.1", "/api/v1/discovery/tiles/r/2/1/3.png")).isEqualTo(429);
    assertThat(request(filter, "198.51.100.2", "/api/v1/discovery/tiles/r/2/1/1.png")).isEqualTo(204);
    assertThat(request(filter, "198.51.100.1", "/api/v1/discovery/configuration")).isEqualTo(204);
  }

  private int request(MapTileRateLimitFilter filter, String peer, String path) throws Exception {
    var req = new MockHttpServletRequest("GET", path);
    req.setServletPath(path);
    req.setRemoteAddr(peer);
    req.addHeader("X-Forwarded-For", "untrusted-spoof");
    var response = new MockHttpServletResponse();
    filter.doFilter(req, response, (r, s) -> ((jakarta.servlet.http.HttpServletResponse)s).setStatus(204));
    return response.getStatus();
  }
}
