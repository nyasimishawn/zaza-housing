package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|::1|0:0:0:0:0:0:0:1",
    "zaza.auth.requests-per-minute=2"
})
@ActiveProfiles("test")
class TrustedProxyRateLimitTests {
  @Value("${local.server.port}") int port;
  final HttpClient client = HttpClient.newHttpClient();

  @Test
  void trustedIngressSeparatesClientsAndUntrustedForwardedHopsCannotChooseTheBucket() throws Exception {
    assertThat(attempt("198.51.100.10")).isEqualTo(400);
    assertThat(attempt("198.51.100.10")).isEqualTo(400);
    assertThat(attempt("198.51.100.10")).isEqualTo(429);
    assertThat(attempt("198.51.100.11")).isEqualTo(400);
    // A client-supplied leftmost value cannot override the nearest untrusted hop.
    assertThat(attempt("198.51.100.99, 198.51.100.11")).isEqualTo(400);
    assertThat(attempt("198.51.100.98, 198.51.100.11")).isEqualTo(429);
  }

  int attempt(String forwarded) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
        .header("X-Forwarded-For", forwarded).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
