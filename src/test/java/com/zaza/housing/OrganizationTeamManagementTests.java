package com.zaza.housing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrganizationTeamManagementTests {
    private static final String PASSWORD = "Workspace-contract-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void ownerCanAddChangeAndRemoveRegisteredMembersWithSafeProjectionsAndAudit() throws Exception {
        Account owner = account(), target = account();
        String org = organization(owner, "AGENCY"), members = path(org) + "/members";
        var added = call("POST", members, Map.of("email", target.email.toUpperCase(), "role", "MEMBER"), owner.token);
        assertThat(added.statusCode()).isEqualTo(201);
        JsonNode member = json.readTree(added.body());
        assertThat(member.get("userId").asText()).isEqualTo(target.id.toString());
        assertThat(member.get("email").asText()).isEqualTo(target.email);
        assertThat(member.get("role").asText()).isEqualTo("MEMBER");
        assertThat(added.body()).doesNotContain("password", "token");
        assertThat(call("PATCH", members + "/" + target.id, Map.of("role", "ADMIN"), owner.token).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", members + "/" + target.id, null, owner.token).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id=? AND entity_id=? AND action IN ('MEMBER_ADDED','MEMBER_ROLE_CHANGED','MEMBER_REMOVED')", Integer.class, UUID.fromString(org), target.id)).isEqualTo(3);
        assertThat(call("GET", path(org), null, target.token).statusCode()).isEqualTo(404);
    }

    @Test
    void administratorCanManageMembersButCannotGrantOrModifyAdministratorOrOwnerAccess() throws Exception {
        Account owner = account(), admin = account(), member = account(), candidate = account();
        String org = organization(owner, "LANDLORD"), members = path(org) + "/members";
        add(owner, org, admin, "ADMIN");
        assertThat(call("POST", members, Map.of("email", member.email, "role", "MEMBER"), admin.token).statusCode()).isEqualTo(201);
        assertThat(call("GET", members, null, admin.token).statusCode()).isEqualTo(200);
        assertThat(call("POST", members, Map.of("email", candidate.email, "role", "ADMIN"), admin.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", members + "/" + member.id, Map.of("role", "ADMIN"), admin.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", members + "/" + admin.id, Map.of("role", "MEMBER"), admin.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", members + "/" + owner.id, null, admin.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", members + "/" + member.id, null, admin.token).statusCode()).isEqualTo(204);
    }

    @Test
    void ownerCannotTransferOwnershipThroughMemberCommands() throws Exception {
        Account owner = account(), candidate = account();
        String org = organization(owner, "SERVICE_PROVIDER"), members = path(org) + "/members";
        assertThat(call("POST", members, Map.of("email", candidate.email, "role", "OWNER"), owner.token).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", members + "/" + owner.id, Map.of("role", "ADMIN"), owner.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", members + "/" + owner.id, null, owner.token).statusCode()).isEqualTo(403);
        assertThat(json.readTree(call("GET", path(org), null, owner.token).body()).get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void demotedAdministratorsAndOrdinaryMembersCannotUseTeamEndpointsWithOldTokens() throws Exception {
        Account owner = account(), admin = account(), candidate = account();
        String org = organization(owner, "AGENCY"), members = path(org) + "/members";
        add(owner, org, admin, "ADMIN");
        assertThat(call("PATCH", members + "/" + admin.id, Map.of("role", "MEMBER"), owner.token).statusCode()).isEqualTo(200);
        assertThat(call("GET", members, null, admin.token).statusCode()).isEqualTo(403);
        assertThat(call("POST", members, Map.of("email", candidate.email, "role", "MEMBER"), admin.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", members + "/" + owner.id, null, admin.token).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/me", null, admin.token).statusCode()).isEqualTo(200);
    }

    @Test
    void duplicateUnknownAndInvalidMemberRequestsAreNotSuccessfulAdditions() throws Exception {
        Account owner = account(), member = account();
        String org = organization(owner, "AGENCY"), members = path(org) + "/members";
        add(owner, org, member, "MEMBER");
        assertThat(call("POST", members, Map.of("email", member.email, "role", "MEMBER"), owner.token).statusCode()).isEqualTo(409);
        assertThat(call("POST", members, Map.of("email", UUID.randomUUID() + "@example.test", "role", "MEMBER"), owner.token).statusCode()).isEqualTo(404);
        assertThat(call("POST", members, Map.of("email", "invalid", "role", "MEMBER"), owner.token).statusCode()).isEqualTo(400);
        assertThat(call("POST", members, Map.of("email", member.email, "role", "PLATFORM_ADMIN"), owner.token).statusCode()).isEqualTo(400);
        assertThat(call("GET", members + "?limit=101", null, owner.token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE organization_id=? AND action='MEMBER_ADDED'", Integer.class, UUID.fromString(org))).isEqualTo(1);
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        var response = call("POST", "/api/v1/auth/register", Map.of("email", email,
                "password", PASSWORD, "displayName", "Workspace contract"), null);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(response.body()).get("id").asText());
        var login = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return new Account(id, email, json.readTree(login.body()).get("accessToken").asText());
    }
    private String organization(Account owner, String type) throws Exception {
        var response = call("POST", "/api/v1/organizations", Map.of("name", "Contract " + type, "type", type), owner.token);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }
    private String path(String id) { return "/api/v1/organizations/" + id; }
    private void add(Account owner, String org, Account target, String role) throws Exception {
        assertThat(call("POST", path(org) + "/members", Map.of("email", target.email, "role", role), owner.token).statusCode()).isEqualTo(201);
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private record Account(UUID id, String email, String token) {}
}
