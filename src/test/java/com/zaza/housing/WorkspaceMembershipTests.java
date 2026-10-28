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
class WorkspaceMembershipTests {
    private static final String PASSWORD = "Workspace-contract-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oneIdentityListsItsLandlordAgencyAndProviderMembershipsWithoutChangingSessions() throws Exception {
        Account user = account(), other = account();
        String landlord = organization(user, "LANDLORD");
        String agency = organization(other, "AGENCY");
        String provider = organization(other, "SERVICE_PROVIDER");
        String foreign = organization(other, "LANDLORD");
        add(other, agency, user, "ADMIN");
        add(other, provider, user, "MEMBER");
        var response = call("GET", "/api/v1/organizations?limit=20&offset=0", null, user.token);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = json.readTree(response.body());
        assertThat(items.size()).isEqualTo(3);
        var ids = new HashSet<String>();
        for (JsonNode item : items) ids.add(item.get("id").asText());
        assertThat(ids).containsExactlyInAnyOrder(landlord, agency, provider).doesNotContain(foreign);
        assertThat(json.readTree(call("GET", path(landlord), null, user.token).body()).get("role").asText()).isEqualTo("OWNER");
        assertThat(json.readTree(call("GET", path(agency), null, user.token).body()).get("role").asText()).isEqualTo("ADMIN");
        assertThat(json.readTree(call("GET", path(provider), null, user.token).body()).get("role").asText()).isEqualTo("MEMBER");
        assertThat(json.readTree(call("GET", "/api/v1/me", null, user.token).body()).get("id").asText()).isEqualTo(user.id.toString());
        assertThat(call("GET", path(foreign), null, user.token).statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("password", "token", other.email);
    }

    @Test
    void membershipPagesAreCompleteBoundedAndNeverMixAnotherAccountsOrganizations() throws Exception {
        Account user = account(), outsider = account();
        var expected = new HashSet<String>();
        for (String type : new String[]{"LANDLORD", "AGENCY", "SERVICE_PROVIDER", "LANDLORD", "AGENCY"})
            expected.add(organization(user, type));
        String foreign = organization(outsider, "AGENCY");
        var seen = new HashSet<String>();
        for (int offset = 0; offset < 6; offset += 2) {
            var response = call("GET", "/api/v1/organizations?limit=2&offset=" + offset, null, user.token);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode page = json.readTree(response.body());
            assertThat(page.size()).isLessThanOrEqualTo(2);
            for (JsonNode item : page) assertThat(seen.add(item.get("id").asText())).isTrue();
        }
        assertThat(seen).isEqualTo(expected).doesNotContain(foreign);
        assertThat(json.readTree(call("GET", "/api/v1/organizations?limit=2&offset=6", null, user.token).body()).size()).isZero();
    }

    @Test
    void roleChangesAndRevocationAreVisibleToAnAlreadyIssuedSession() throws Exception {
        Account owner = account(), user = account();
        String org = organization(owner, "AGENCY");
        add(owner, org, user, "ADMIN");
        assertThat(call("PATCH", path(org) + "/members/" + user.id, Map.of("role", "MEMBER"), owner.token).statusCode()).isEqualTo(200);
        assertThat(json.readTree(call("GET", path(org), null, user.token).body()).get("role").asText()).isEqualTo("MEMBER");
        assertThat(call("GET", path(org) + "/members", null, user.token).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", path(org) + "/members/" + user.id, null, owner.token).statusCode()).isEqualTo(204);
        assertThat(call("GET", path(org), null, user.token).statusCode()).isEqualTo(404);
        assertThat(json.readTree(call("GET", "/api/v1/organizations", null, user.token).body()).size()).isZero();
        assertThat(call("GET", "/api/v1/me", null, user.token).statusCode()).isEqualTo(200);
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
