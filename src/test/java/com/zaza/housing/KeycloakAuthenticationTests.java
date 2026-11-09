package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import com.zaza.housing.util.TokenUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"zaza.keycloak.enabled=true", "zaza.security.require-admin-mfa=false",
        "zaza.documents.keys=keycloak-test:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zaza.documents.active-key-id=keycloak-test"})
@ActiveProfiles("test")
class KeycloakAuthenticationTests {
  private static final String PASSWORD = "Keycloak-test-password-42";
  private static final Provider PROVIDER = new Provider();
  private final HttpClient client = HttpClient.newHttpClient();
  @Value("${local.server.port}") int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    registry.add("zaza.keycloak.server-url", PROVIDER::base);
    registry.add("zaza.keycloak.realm", () -> "zaza");
    registry.add("zaza.keycloak.client-id", () -> "zaza-backend");
    registry.add("zaza.keycloak.client-secret", () -> "test-client-secret");
  }

  @Test
  void registerLoginRefreshAndLogoutPreserveIdentityAndRevokeRotatedSessions() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var registered = call("POST", "/api/v1/auth/register", Map.of("email", email,
        "password", PASSWORD, "displayName", "Keycloak User", "platformRole", "PLATFORM_ADMIN"), null);
    assertThat(registered.statusCode()).isEqualTo(201);
    var user = json.readTree(registered.body());
    assertThat(user.get("platformRole").asText()).isEqualTo("USER");
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", email,
        "password", "Incorrect-password-42"), null).statusCode()).isEqualTo(401);
    var first = login(email);
    String access = first.get("accessToken").asText();
    String refresh = first.get("refreshToken").asText();
    assertThat(access.split("\\.")).hasSize(3);
    assertThat(call("GET", "/api/v1/me", null, access).statusCode()).isEqualTo(200);
    assertThat(call("GET", "/api/v1/admin/users", null, access).statusCode()).isEqualTo(403);
    String encrypted = jdbc.queryForObject("SELECT encrypted_refresh_token FROM keycloak_sessions WHERE token_hash=?",
        String.class, TokenUtils.hashToken(access));
    assertThat(encrypted).doesNotContain(refresh);
    var rotated = call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", refresh), null);
    assertThat(rotated.statusCode()).isEqualTo(200);
    var next = json.readTree(rotated.body());
    assertThat(next.get("user").get("id").asText()).isEqualTo(user.get("id").asText());
    assertThat(call("GET", "/api/v1/me", null, access).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", refresh), null).statusCode()).isEqualTo(401);
    String newAccess = next.get("accessToken").asText();
    assertThat(call("POST", "/api/v1/auth/logout", null, newAccess).statusCode()).isEqualTo(204);
    assertThat(call("GET", "/api/v1/me", null, newAccess).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", next.get("refreshToken").asText()), null).statusCode()).isEqualTo(401);
  }

  @Test
  void verifiedJwtStillRequiresALocalSessionAndLocalActiveAccount() throws Exception {
    var login = registeredLogin();
    String access = login.get("accessToken").asText();
    UUID id = UUID.fromString(login.get("user").get("id").asText());
    String subject = jdbc.queryForObject("SELECT subject FROM keycloak_identities WHERE user_id=?", String.class, id);
    String email = login.get("user").get("email").asText();
    String unregisteredToken = PROVIDER.jwt(subject, email, PROVIDER.issuer(), "zaza-backend", "zaza-backend", Instant.now().plusSeconds(300));
    assertThat(call("GET", "/api/v1/me", null, unregisteredToken).statusCode()).isEqualTo(401);
    jdbc.update("UPDATE app_users SET active=false WHERE id=?", id);
    assertThat(call("GET", "/api/v1/me", null, access).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", login.get("refreshToken").asText()), null).statusCode()).isEqualTo(401);
  }

  @Test
  void wrongIssuerAudienceClientExpiryAndSignatureCannotBypassStoredSession() throws Exception {
    var login = registeredLogin();
    UUID id = UUID.fromString(login.get("user").get("id").asText());
    String subject = jdbc.queryForObject("SELECT subject FROM keycloak_identities WHERE user_id=?", String.class, id);
    String email = login.get("user").get("email").asText();
    for (String token : List.of(
        PROVIDER.jwt(subject, email, "http://localhost/realms/spiro", "zaza-backend", "zaza-backend", Instant.now().plusSeconds(300)),
        PROVIDER.jwt(subject, email, PROVIDER.issuer(), "another-api", "zaza-backend", Instant.now().plusSeconds(300)),
        PROVIDER.jwt(subject, email, PROVIDER.issuer(), "zaza-backend", "another-client", Instant.now().plusSeconds(300)),
        PROVIDER.jwt(subject, email, PROVIDER.issuer(), "zaza-backend", "zaza-backend", Instant.now().minusSeconds(120)))) {
      jdbc.update("INSERT INTO auth_sessions(token_hash,user_id,created_at,expires_at) VALUES(?,?,now(),now()+interval '5 minutes')", TokenUtils.hashToken(token), id);
      assertThat(call("GET", "/api/v1/me", null, token).statusCode()).isEqualTo(401);
    }
    String original = login.get("accessToken").asText();
    String invalid = original.substring(0, original.lastIndexOf('.') + 1) + "invalidsignature";
    assertThat(call("GET", "/api/v1/me", null, invalid).statusCode()).isEqualTo(401);
  }

  @Test
  void emailCollisionNeverLinksAnExistingAccount() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    UUID existing = UUID.randomUUID();
    jdbc.update("INSERT INTO app_users(id,email,display_name,password_hash) VALUES(?,?,?,?)", existing, email, "Existing owner", "local-only");
    PROVIDER.add(email, PASSWORD);
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM keycloak_identities WHERE user_id=?", Integer.class, existing)).isZero();
  }

  @Test
  void organizationIsolationAndPasswordConfirmationWorkForKeycloakUsers() throws Exception {
    var owner = registeredLogin();
    var outsider = registeredLogin();
    String token = owner.get("accessToken").asText();
    var organization = call("POST", "/api/v1/organizations", Map.of("name", "Keycloak agency", "type", "AGENCY"), token);
    assertThat(organization.statusCode()).isEqualTo(201);
    String id = json.readTree(organization.body()).get("id").asText();
    assertThat(call("GET", "/api/v1/organizations/" + id, null, outsider.get("accessToken").asText()).statusCode()).isEqualTo(404);
    var revoke = call("POST", "/api/v1/me/security/sessions/revoke-others", Map.of("password", PASSWORD), token);
    // Exercise the actual password-confirming route, including the remote credential check.
    assertThat(revoke.statusCode()).isEqualTo(200);
  }

  @Test
  void passwordChangeUpdatesProviderAndRevokesAccessAndRefresh() throws Exception {
    var login = registeredLogin();
    String email = login.get("user").get("email").asText();
    String token = login.get("accessToken").asText();
    var change = call("POST", "/api/v1/me/security/password", Map.of("password", PASSWORD, "newPassword", "New-Keycloak-password-42"), token);
    assertThat(change.statusCode()).isEqualTo(200);
    assertThat(call("GET", "/api/v1/me", null, token).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/login", Map.of("email", email, "password", "New-Keycloak-password-42"), null).statusCode()).isEqualTo(200);
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", login.get("refreshToken").asText()), null).statusCode()).isEqualTo(401);
  }

  @Test
  void refreshKeepsOriginalMfaProofAndAbsoluteSessionLimit() throws Exception {
    var login = registeredLogin();
    String hash = TokenUtils.hashToken(login.get("accessToken").asText());
    var proof = java.sql.Timestamp.from(Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    var limit = jdbc.queryForObject("SELECT session_expires_at FROM keycloak_sessions WHERE token_hash=?", java.sql.Timestamp.class, hash);
    jdbc.update("UPDATE auth_sessions SET mfa_verified_at=? WHERE token_hash=?", proof, hash);
    var rotated = call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", login.get("refreshToken").asText()), null);
    assertThat(rotated.statusCode()).isEqualTo(200);
    String nextHash = TokenUtils.hashToken(json.readTree(rotated.body()).get("accessToken").asText());
    assertThat(jdbc.queryForObject("SELECT mfa_verified_at FROM auth_sessions WHERE token_hash=?", java.sql.Timestamp.class, nextHash)).isEqualTo(proof);
    assertThat(jdbc.queryForObject("SELECT session_expires_at FROM keycloak_sessions WHERE token_hash=?", java.sql.Timestamp.class, nextHash)).isEqualTo(limit);
  }

  @Test
  void expiredAccessCanRefreshButLocallyRevokedSessionCannot() throws Exception {
    var login = registeredLogin();
    String hash = TokenUtils.hashToken(login.get("accessToken").asText());
    UUID stableSession = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE token_hash=?", UUID.class, hash);
    UUID user = UUID.fromString(login.get("user").get("id").asText());
    UUID device = UUID.randomUUID();
    jdbc.update("INSERT INTO notification_devices(id,user_id,installation_id,session_id,platform,label,encrypted_token,token_hash,created_at,updated_at) VALUES(?,?,?,?,'ANDROID','Keycloak device','test-only',?,now(),now())",
        device, user, UUID.randomUUID(), stableSession, TokenUtils.hashToken(UUID.randomUUID().toString()));
    jdbc.update("UPDATE auth_sessions SET created_at=now()-interval '10 minutes',expires_at=now()-interval '1 minute' WHERE token_hash=?", hash);
    assertThat(call("GET", "/api/v1/me", null, login.get("accessToken").asText()).statusCode()).isEqualTo(401);
    var rotated = call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", login.get("refreshToken").asText()), null);
    assertThat(rotated.statusCode()).isEqualTo(200);
    var next = json.readTree(rotated.body());
    assertThat(jdbc.queryForObject("SELECT id FROM auth_sessions WHERE token_hash=?", UUID.class,
        TokenUtils.hashToken(next.get("accessToken").asText()))).isEqualTo(stableSession);
    assertThat(jdbc.queryForObject("SELECT session_id FROM notification_devices WHERE id=?", UUID.class, device)).isEqualTo(stableSession);
    jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?", TokenUtils.hashToken(next.get("accessToken").asText()));
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", next.get("refreshToken").asText()), null).statusCode()).isEqualTo(401);
  }

  @Test
  void securityScreenShowsRefreshableSessionsAfterTheirAccessTokenExpires() throws Exception {
    var first = registeredLogin();
    String email = first.get("user").get("email").asText();
    var second = login(email);
    String hash = TokenUtils.hashToken(first.get("accessToken").asText());
    UUID session = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE token_hash=?", UUID.class, hash);
    jdbc.update("UPDATE auth_sessions SET created_at=now()-interval '10 minutes',expires_at=now()-interval '1 minute' WHERE token_hash=?", hash);
    var state = call("GET", "/api/v1/me/security", null, second.get("accessToken").asText());
    assertThat(state.statusCode()).isEqualTo(200);
    assertThat(state.body()).contains(session.toString());
    assertThat(call("POST", "/api/v1/me/security/sessions/" + session + "/revoke", null, second.get("accessToken").asText()).statusCode()).isEqualTo(200);
    assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", first.get("refreshToken").asText()), null).statusCode()).isEqualTo(401);
  }

  @org.junit.jupiter.api.AfterAll
  static void stopProvider() { PROVIDER.server.stop(0); }

  private JsonNode registeredLogin() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    assertThat(call("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Keycloak Tester"), null).statusCode()).isEqualTo(201);
    return login(email);
  }

  private JsonNode login(String email) throws Exception {
    var response = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A signed OIDC test provider: real RSA/JWKS, HTTP forms, rotating refresh tokens, admin API. */
  private static class Provider {
    final ObjectMapper json = new ObjectMapper();
    final HttpServer server;
    final RSAKey key;
    final Map<String, Identity> users = new ConcurrentHashMap<>();
    final Map<String, Identity> refreshes = new ConcurrentHashMap<>();

    Provider() {
      try {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID("test-key").build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }));
        server.start();
      } catch (Exception e) { throw new IllegalStateException(e); }
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String issuer() { return base() + "/realms/zaza"; }
    Identity add(String email, String password) {
      var user = new Identity(UUID.randomUUID().toString(), email, password);
      users.put(email, user);
      return user;
    }

    String jwt(String subject, String email, String issuer, String audience, String azp, Instant expires) throws Exception {
      var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
          .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(expires))
          .jwtID(UUID.randomUUID().toString()).claim("email", email).claim("name", "Test Identity")
          .claim("azp", azp).claim("typ", "Bearer").build();
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    }

    void handle(HttpExchange exchange) throws java.io.IOException {
      try {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/certs")) { send(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()); return; }
        if (path.endsWith("/token") || path.endsWith("/logout") && path.contains("openid-connect")) {
          Map<String, String> form = new HashMap<>();
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          for (String part : body.split("&")) { var kv = part.split("=", 2); form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8)); }
          if (!"test-client-secret".equals(form.get("client_secret"))) { send(exchange, 401, Map.of("error", "invalid_client")); return; }
          if (path.endsWith("/logout")) { refreshes.remove(form.get("refresh_token")); send(exchange, 204, null); return; }
          if ("client_credentials".equals(form.get("grant_type"))) { send(exchange, 200, Map.of("access_token", "admin-token")); return; }
          Identity user;
          if ("refresh_token".equals(form.get("grant_type"))) user = refreshes.remove(form.get("refresh_token"));
          else { user = users.get(form.get("username")); if (user != null && !user.password.equals(form.get("password"))) user = null; }
          if (user == null) { send(exchange, 400, Map.of("error", "invalid_grant")); return; }
          String refresh = UUID.randomUUID().toString();
          refreshes.put(refresh, user);
          send(exchange, 200, Map.of("access_token", jwt(user.id, user.email, issuer(), "zaza-backend", "zaza-backend", Instant.now().plusSeconds(300)),
              "refresh_token", refresh, "expires_in", 300, "refresh_expires_in", 3600));
          return;
        }
        if (path.startsWith("/admin/realms/zaza/users")) {
          if (!"Bearer admin-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { send(exchange, 403, Map.of()); return; }
          if (path.equals("/admin/realms/zaza/users") && exchange.getRequestMethod().equals("POST")) {
            var data = json.readTree(exchange.getRequestBody());
            String email = data.get("email").asText();
            if (users.containsKey(email)) { send(exchange, 409, Map.of()); return; }
            var user = add(email, data.get("credentials").get(0).get("value").asText());
            exchange.getResponseHeaders().set("Location", base() + path + "/" + user.id);
            send(exchange, 201, null); return;
          }
          String subject = path.split("/")[5];
          var user = users.values().stream().filter(u -> u.id.equals(subject)).findFirst().orElseThrow();
          if (path.endsWith("/reset-password")) user.password = json.readTree(exchange.getRequestBody()).get("value").asText();
          if (path.endsWith("/logout")) refreshes.entrySet().removeIf(e -> e.getValue().id.equals(subject));
          send(exchange, 204, null); return;
        }
        send(exchange, 404, Map.of());
      } catch (Exception e) { send(exchange, 500, Map.of("error", "test_provider_failure")); }
    }

    void send(HttpExchange exchange, int status, Object body) throws java.io.IOException {
      if (body == null) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
      byte[] bytes = json.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
      exchange.close();
    }

    private static class Identity {
      final String id;
      final String email;
      volatile String password;
      Identity(String id, String email, String password) { this.id = id; this.email = email; this.password = password; }
    }
  }
}
