package com.zaza.housing.config;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.filter.AuthRateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

class BrowserRateLimitCorsTests {
  @Test
  void browserCanReadRetryAfterOnAnActualRateLimitResponse() throws Exception {
    var cors = new CorsFilter(new SecurityConfig().corsConfiguration("http://localhost:3000"));
    var limiter = new AuthRateLimitFilter(Clock.systemUTC(), 1);
    for (int attempt = 0; attempt < 2; attempt++) {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
      request.setServletPath("/api/v1/auth/login");
      request.setRemoteAddr("127.0.0.1");
      request.addHeader("Origin", "http://localhost:3000");
      var response = new MockHttpServletResponse();
      cors.doFilter(
          request,
          response,
          (req, res) ->
              limiter.doFilter(
                  req,
                  res,
                  (allowedRequest, allowedResponse) ->
                      ((HttpServletResponse) allowedResponse).setStatus(204)));
      assertThat(response.getHeader("Access-Control-Allow-Origin"))
          .isEqualTo("http://localhost:3000");
      assertThat(response.getHeader("Access-Control-Expose-Headers"))
          .contains("Retry-After", "Accept-Ranges", "Content-Range", "Content-Length");
      assertThat(response.getStatus()).isEqualTo(attempt == 0 ? 204 : 429);
      if (attempt == 1) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }
  }

  @Test
  void unapprovedOriginsStillCannotReadApiResponses() throws Exception {
    var cors = new CorsFilter(new SecurityConfig().corsConfiguration("http://localhost:3000"));
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.addHeader("Origin", "https://unapproved.example");
    var response = new MockHttpServletResponse();
    cors.doFilter(
        request,
        response,
        (req, res) -> {
          throw new AssertionError("Rejected origin reached handler");
        });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    assertThat(response.getHeader("Access-Control-Expose-Headers")).isNull();
  }
}
