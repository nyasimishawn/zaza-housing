package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;
import com.zaza.housing.dto.DiscoveryCommandRequest;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.repository.MediaQuotaRepository;
import com.zaza.housing.repository.PropertyImageRepository;
import com.zaza.housing.repository.PropertyVideoRepository;
import com.zaza.housing.service.AuthService;
import com.zaza.housing.service.MediaQuotaService;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "zaza.media.account-bytes=100", "zaza.media.organization-bytes=100"
})
@ActiveProfiles("test")
class SecurityRemediationTests {
  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    Clock fixedSecurityClock() {
      var clock = Clock.systemUTC();
      return Clock.fixed(clock.instant(), clock.getZone());
    }
  }

  static final String PASSWORD = "Security-remediation-password-42";
  @Value("${local.server.port}") int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired MediaQuotaRepository repository;
  @Autowired PropertyImageRepository images;
  @Autowired PropertyVideoRepository videos;
  @Autowired AuthService auth;
  @Autowired PlatformTransactionManager manager;
  final HttpClient client = HttpClient.newHttpClient();

  @Test
  void registrationDoesNotDiscloseOrOverwriteAnExistingAccount() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var first = call("POST", "/api/v1/auth/register", Map.of("email", email,
        "password", PASSWORD, "displayName", "Original name"), null);
    assertThat(first.statusCode()).isEqualTo(202);
    String originalHash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email=?", String.class, email);
    var duplicate = call("POST", "/api/v1/auth/register", Map.of("email", email,
        "password", "Different-registration-password-42", "displayName", "Replacement name"), null);
    assertThat(duplicate.statusCode()).isEqualTo(first.statusCode());
    assertThat(duplicate.body()).isEqualTo(first.body());
    assertThat(first.body()).doesNotContain(email, "id", "platformRole", "Original name");
    assertThat(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email=?", String.class, email)).isEqualTo(originalHash);
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(200);
  }

  @Test
  void managerCannotAddUnverifiedIdentityButCanProvisionVerifiedRecipient() throws Exception {
    var owner = account(); var target = account();
    var org = call("POST", "/api/v1/organizations", Map.of("name", "Verified members", "type", "LANDLORD"), owner.token);
    String id = json.readTree(org.body()).get("id").asText();
    String members = "/api/v1/organizations/" + id + "/members";
    var body = Map.of("email", target.email, "role", "ADMIN");
    var unverified = call("POST", members, body, owner.token);
    assertThat(unverified.statusCode()).isEqualTo(404);
    var absent = call("POST", members, Map.of("email", UUID.randomUUID() + "@example.test", "role", "ADMIN"), owner.token);
    assertThat(absent.statusCode()).isEqualTo(unverified.statusCode());
    assertThat(json.readTree(absent.body()).get("detail")).isEqualTo(json.readTree(unverified.body()).get("detail"));
    assertThat(call("GET", "/api/v1/organizations/" + id, null, target.token).statusCode()).isEqualTo(404);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE organization_id=? AND user_id=?",
        Integer.class, UUID.fromString(id), target.id)).isZero();
    jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?", target.id);
    assertThat(call("POST", members, body, owner.token).statusCode()).isEqualTo(201);
    assertThat(call("GET", "/api/v1/organizations/" + id, null, target.token).statusCode()).isEqualTo(200);
  }

  @Test
  void springMapperRejectsNumericStringsQuicklyAndRetainsDecimalMoneyCompatibility() throws Exception {
    String malformed = "1".repeat(16000) + "x";
    String input = "{\"query\":{\"latitude\":\"" + malformed + "\"}}";
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
        assertThatThrownBy(() -> json.readValue(input, DiscoveryCommandRequest.class)).isInstanceOf(Exception.class));
    var valid = json.readValue("{\"query\":{\"latitude\":-1.2,\"minRent\":\"25000.50\"}}", DiscoveryCommandRequest.class);
    assertThat(valid.query().latitude()).isEqualTo(-1.2);
    assertThat(valid.query().minRent()).isEqualByComparingTo("25000.50");
    assertThatThrownBy(() -> json.readTree("{\"text\":\"" + "x".repeat(65537) + "\"}"))
        .isInstanceOf(Exception.class);
    var actor = account();
    assertThat(call("POST", "/api/v1/me/discovery/searches",
        Map.of("requestId", UUID.randomUUID(), "name", "Malformed", "query", Map.of("latitude", malformed)), actor.token)
        .statusCode()).isEqualTo(400);
  }

  @Test
  void localLoginThrottlePersistsAfterFailedTransactionsAndNormalizesEmail() throws Exception {
    var actor = account();
    for (int i = 0; i < 21; i++) {
      assertThat(auth.login(actor.email, PASSWORD).user().id()).isEqualTo(actor.id);
    }
    for (int i = 0; i < 20; i++) {
      assertThat(call("POST", "/api/v1/auth/login", Map.of("email", actor.email.toUpperCase(),
          "password", "Wrong-security-password-42"), null).statusCode()).isEqualTo(401);
    }
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", actor.email, "password", PASSWORD), null)
        .statusCode()).isEqualTo(429);
    assertThat(auth.login(actor.email, PASSWORD, null, null, "192.0.2.1").user().id())
        .isEqualTo(actor.id);
    assertThat(call("GET", "/api/v1/me", null, actor.token).statusCode()).isEqualTo(200);
  }

  @Test
  void retainedQuotasCountArchivedImagesAndVideosAndSerializeConcurrentReservations() throws Exception {
    var actor = account();
    UUID org = UUID.randomUUID(), property = UUID.randomUUID();
    jdbc.update("INSERT INTO organizations(id,name,type,created_by) VALUES (?, 'Quota fixture', 'LANDLORD', ?)", org, actor.id);
    jdbc.update("INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'OWNER')", org, actor.id);
    jdbc.update("INSERT INTO properties(id,owner_organization_id,name,type,country_code,city,address,created_by) VALUES (?,?,'Quota fixture','HOUSE','KE','Nairobi','Fixture',?)", property, org, actor.id);
    jdbc.update("INSERT INTO property_images(id,property_id,created_by,storage_key,content_type,width,height,byte_size,status) VALUES (?,?,?,?,'image/png',1,1,60,'ARCHIVED')", UUID.randomUUID(), property, actor.id, UUID.randomUUID() + ".png");
    jdbc.update("INSERT INTO property_videos(id,property_id,created_by,storage_key,content_type,width,height,duration_seconds,byte_size,status) VALUES (?,?,?,?,'video/mp4',2,2,1,40,'ARCHIVED')", UUID.randomUUID(), property, actor.id, UUID.randomUUID() + ".mp4");
    var tx = new TransactionTemplate(manager);
    var quota = new MediaQuotaService(repository, images, videos, 100, 100);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.requireCapacity(actor.id, org, 1)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
    var other = account();
    String propertyPath = "/api/v1/organizations/" + org + "/properties/" + property;
    var usage = call("GET", propertyPath + "/media-quota", null, actor.token);
    assertThat(usage.statusCode()).isEqualTo(200);
    assertThat(usage.headers().firstValue("Cache-Control")).hasValue("no-store");
    var quotaResponse = json.readTree(usage.body());
    assertThat(quotaResponse.path("account").path("usage").path("bytes").asLong()).isEqualTo(100);
    assertThat(quotaResponse.path("owningOrganization").path("byteLimit").asLong()).isEqualTo(100);
    assertThat(quotaResponse.path("retainedImages").asInt()).isEqualTo(1);
    assertThat(quotaResponse.path("retainedVideos").asInt()).isEqualTo(1);
    assertThat(call("GET", propertyPath + "/media-quota", null, other.token).statusCode()).isEqualTo(404);
    var upload = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + propertyPath + "/videos"))
        .header("Authorization", "Bearer " + actor.token)
        .header("Content-Type", "multipart/form-data; boundary=zaza-test")
        .POST(HttpRequest.BodyPublishers.ofString(
            "--zaza-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rejected.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\ninvalid video\r\n--zaza-test--\r\n"))
        .build();
    assertThat(client.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.requireCapacity(other.id, org, 1)))
        .isInstanceOf(ApiException.class); // The owner's quota applies to other uploaders too.
    assertThat(repository.usage(actor.id, false).bytes()).isEqualTo(100);
    var larger = new MediaQuotaService(repository, images, videos, 150, 150);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> save = () -> {
        start.await();
        try {
          tx.executeWithoutResult(s -> {
            larger.requireCapacity(actor.id, org, 40);
            jdbc.update("INSERT INTO property_images(id,property_id,created_by,storage_key,content_type,width,height,byte_size) VALUES (?,?,?,?,'image/png',1,1,40)", UUID.randomUUID(), property, actor.id, UUID.randomUUID() + ".png");
          });
          return 201;
        } catch (ApiException e) { return e.status().value(); }
      };
      var a = pool.submit(save); var b = pool.submit(save); start.countDown();
      assertThat(List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(repository.usage(actor.id, false).bytes()).isEqualTo(140);
  }

  Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var registered = call("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Security fixture"), null);
    assertThat(registered.statusCode()).isEqualTo(202);
    UUID id = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", UUID.class, email);
    var login = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
    assertThat(login.statusCode()).isEqualTo(200);
    return new Account(id, email, json.readTree(login.body()).get("accessToken").asText());
  }

  HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body != null) builder.header("Content-Type", "application/json");
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  record Account(UUID id, String email, String token) {}
}
