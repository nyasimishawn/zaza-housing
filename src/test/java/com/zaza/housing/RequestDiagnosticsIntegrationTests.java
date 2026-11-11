package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RequestDiagnosticsIntegrationTests {
  @Value("${local.server.port}") int port;
  @Autowired ObjectMapper json;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test void securityAndControllerErrorsShareTheSamePublicContract() throws Exception {
    String supplied = UUID.randomUUID().toString();
    for (String path : new String[] {"/api/v1/me", "/api/v1/listings/" + UUID.randomUUID()}) {
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .header("Origin", "http://localhost:3000").header("X-Request-ID", supplied).GET().build(), HttpResponse.BodyHandlers.ofString());
      var body = json.readTree(response.body());
      String id = response.headers().firstValue("X-Request-ID").orElseThrow();
      assertThat(UUID.fromString(id)).isNotNull();
      assertThat(id).isNotEqualTo(supplied);
      assertThat(body.get("requestId").asText()).isEqualTo(id);
      assertThat(body.get("code").asText()).isEqualTo(path.endsWith("/me") ? "AUTHENTICATION_REQUIRED" : "RESOURCE_NOT_FOUND");
      assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("X-Request-ID");
    }
  }

  @Test void incorrectCredentialsHaveAStableCodeAndNoAccountEnumeration() throws Exception {
    var body = json.writeValueAsString(java.util.Map.of("email", UUID.randomUUID() + "@example.test", "password", "synthetic-wrong-password-42"));
    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.body()).doesNotContain("synthetic-wrong-password-42", "@example.test");
  }
}
