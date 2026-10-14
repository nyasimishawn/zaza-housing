package com.zaza.housing;

import com.zaza.housing.auth.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HousingApplicationTests {
    private static final String PASSWORD = "Foundation-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void onlyHealthAndAccountEntryPointsArePublic() throws Exception {
        assertThat(call("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
        for (String path : new String[]{"/api/v1/me", "/api/v1/organizations", "/api/v1/admin/users"}) {
            assertThat(call("GET", path, null, null).statusCode()).isEqualTo(401);
        }
        assertThat(call("POST", "/api/v1/organizations", Map.of("name", "Private", "type", "AGENCY"), null).statusCode()).isEqualTo(401);
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndCannotGrantAdmin() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var response = call("POST", "/api/v1/auth/register", Map.of("email", email.toUpperCase(),
                "password", PASSWORD, "displayName", "Test account", "platformRole", "PLATFORM_ADMIN"), null);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode account = json.readTree(response.body());
        assertThat(account.get("email").asText()).isEqualTo(email);
        assertThat(account.get("platformRole").asText()).isEqualTo("USER");
        assertThat(response.body()).doesNotContain("password", PASSWORD);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(call("POST", "/api/v1/auth/register", Map.of("email", email,
                "password", PASSWORD, "displayName", "Duplicate"), null).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/v1/auth/register", Map.of("email", UUID.randomUUID() + "@example.com",
                "password", "é".repeat(40), "displayName", "Too many UTF-8 bytes"), null).statusCode()).isEqualTo(400);
    }

    @Test
    void loginLogoutAndIndependentSessionsWork() throws Exception {
        Account account = account();
        assertThat(call("POST", "/api/v1/auth/login", Map.of("email", account.email,
                "password", "Incorrect-password-42"), null).statusCode()).isEqualTo(401);
        String secondToken = login(account.email);
        assertThat(call("GET", "/api/v1/me", null, account.token).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash = ?", Integer.class,
                AuthService.hashToken(account.token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash = ?", Integer.class,
                account.token)).isZero();
        assertThat(call("POST", "/api/v1/auth/logout", null, account.token).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/me", null, account.token).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/me", null, secondToken).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/me", null, "invalid").statusCode()).isEqualTo(401);
    }

    @Test
    void expiredSessionsAndDisabledAccountsAreRejected() throws Exception {
        Account expired = account();
        jdbc.update("UPDATE auth_sessions SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE token_hash = ?",
                AuthService.hashToken(expired.token));
        assertThat(call("GET", "/api/v1/me", null, expired.token).statusCode()).isEqualTo(401);
        Account disabled = account();
        jdbc.update("UPDATE app_users SET active = false WHERE id = ?", disabled.id);
        assertThat(call("GET", "/api/v1/me", null, disabled.token).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/auth/login", Map.of("email", disabled.email, "password", PASSWORD), null).statusCode()).isEqualTo(401);
    }

    @Test
    void organizationsAreIsolatedForReadsAndWrites() throws Exception {
        Account first = account();
        Account second = account();
        String organizationA = organization(first);
        String organizationB = organization(second);
        JsonNode list = json.readTree(call("GET", "/api/v1/organizations", null, first.token).body());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(organizationA);
        String foreign = "/api/v1/organizations/" + organizationB;
        assertThat(call("GET", foreign, null, first.token).statusCode()).isEqualTo(404);
        assertThat(call("GET", foreign + "/members", null, first.token).statusCode()).isEqualTo(404);
        assertThat(call("PATCH", foreign, Map.of("name", "Unauthorized rename"), first.token).statusCode()).isEqualTo(404);
        assertThat(call("POST", foreign + "/members", Map.of("email", first.email, "role", "ADMIN"), first.token).statusCode()).isEqualTo(404);
        assertThat(call("DELETE", foreign + "/members/" + second.id, null, first.token).statusCode()).isEqualTo(404);
        assertThat(call("GET", foreign, null, second.token).body()).doesNotContain("Unauthorized rename");
    }

    @Test
    void membershipPermissionsPreventEscalationAndRevocationTakesEffectImmediately() throws Exception {
        Account owner = account();
        Account member = account();
        String organization = organization(owner);
        String base = "/api/v1/organizations/" + organization;
        assertThat(call("POST", base + "/members", Map.of("email", member.email, "role", "MEMBER"), owner.token).statusCode()).isEqualTo(201);
        assertThat(call("GET", base, null, member.token).statusCode()).isEqualTo(200);
        assertThat(call("GET", base + "/members", null, member.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", base, Map.of("name", "Unauthorized"), member.token).statusCode()).isEqualTo(403);
        assertThat(call("POST", base + "/members", Map.of("email", member.email, "role", "ADMIN"), member.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", base + "/members/" + member.id, Map.of("role", "ADMIN"), member.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", base + "/members/" + member.id, Map.of("role", "ADMIN"), owner.token).statusCode()).isEqualTo(200);
        assertThat(call("GET", base + "/members", null, member.token).statusCode()).isEqualTo(200);
        assertThat(call("PATCH", base + "/members/" + member.id, Map.of("role", "OWNER"), member.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", base + "/members/" + owner.id, null, member.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", base + "/members/" + member.id, null, owner.token).statusCode()).isEqualTo(204);
        assertThat(call("GET", base, null, member.token).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/me", null, member.token).statusCode()).isEqualTo(200);
    }

    @Test
    void ownerCannotBeRemovedOrDemotedAndDuplicateMembershipIsRejected() throws Exception {
        Account owner = account();
        Account member = account();
        String base = "/api/v1/organizations/" + organization(owner);
        assertThat(call("DELETE", base + "/members/" + owner.id, null, owner.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", base + "/members/" + owner.id, Map.of("role", "MEMBER"), owner.token).statusCode()).isEqualTo(403);
        assertThat(call("POST", base + "/members", Map.of("email", member.email, "role", "OWNER"), owner.token).statusCode()).isEqualTo(403);
        assertThat(call("POST", base + "/members", Map.of("email", member.email, "role", "MEMBER"), owner.token).statusCode()).isEqualTo(201);
        assertThat(call("POST", base + "/members", Map.of("email", member.email, "role", "MEMBER"), owner.token).statusCode()).isEqualTo(409);
    }

    @Test
    void platformAdminDoesNotAutomaticallyGetPrivateOrganizationAccess() throws Exception {
        Account admin = account();
        Account owner = account();
        String privateOrg = organization(owner);
        assertThat(call("GET", "/api/v1/admin/users", null, admin.token).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE app_users SET platform_role = 'PLATFORM_ADMIN' WHERE id = ?", admin.id);
        assertThat(call("GET", "/api/v1/admin/users", null, admin.token).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/organizations/" + privateOrg, null, admin.token).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/admin/users?limit=101", null, admin.token).statusCode()).isEqualTo(400);
    }

    @Test
    void changesAreAuditedAndAuditHistoryCannotBeUpdatedOrDeleted() throws Exception {
        Account owner = account();
        String organization = organization(owner);
        assertThat(call("PATCH", "/api/v1/organizations/" + organization,
                Map.of("name", "Renamed organization"), owner.token).statusCode()).isEqualTo(200);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_id = ? AND action IN ('USER_REGISTERED', 'USER_LOGGED_IN', 'ORGANIZATION_CREATED', 'ORGANIZATION_RENAMED')", Integer.class, owner.id);
        assertThat(count).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT changes ->> 'newValue' FROM audit_events WHERE actor_id = ? AND action = 'ORGANIZATION_RENAMED'", String.class, owner.id))
                .isEqualTo("Renamed organization");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'TAMPERED' WHERE actor_id = ?", owner.id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE actor_id = ?", owner.id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void requestValidationAndPaginationAreEnforced() throws Exception {
        Account account = account();
        assertThat(call("POST", "/api/v1/organizations", Map.of("name", " ", "type", "AGENCY"), account.token).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/organizations", Map.of("name", "Invalid", "type", "UNKNOWN"), account.token).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/organizations?offset=-1", null, account.token).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/organizations/not-a-uuid", null, account.token).statusCode()).isEqualTo(400);
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var response = call("POST", "/api/v1/auth/register", Map.of("email", email,
                "password", PASSWORD, "displayName", "Foundation test"), null);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(response.body()).get("id").asText());
        return new Account(id, email, login(email));
    }

    private String login(String email) throws Exception {
        var response = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("accessToken").asText();
    }

    private String organization(Account owner) throws Exception {
        var response = call("POST", "/api/v1/organizations", Map.of("name", "Test organization", "type", "AGENCY"), owner.token);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Account(UUID id, String email, String token) { }
}
