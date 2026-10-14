package com.zaza.housing;

import com.zaza.housing.model.Property;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PropertyManagementTests {
    private static final String PASSWORD = "Property-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void propertyLifecycleNormalizesLocationsAndSupportsPrivateFilters() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        JsonNode property = createProperty(owner, propertyDetails("  Riverside  "));
        String path = owner.base() + "/" + property.get("id").asText();
        assertThat(property.get("name").asText()).isEqualTo("Riverside");
        assertThat(property.get("countryCode").asText()).isEqualTo("KE");
        assertThat(property.get("status").asText()).isEqualTo("DRAFT");
        assertThat(property.get("ownerOrganizationId").asText()).isEqualTo(owner.organization);
        assertThat(property.get("managerOrganizationId").isNull()).isTrue();
        assertThat(property.get("version").asLong()).isZero();
        assertThat(node(call("GET", owner.base() + "?type=HOUSE&city=nairobi", null, owner.account.token)).size()).isEqualTo(1);
        assertThat(node(call("GET", owner.base() + "?status=ACTIVE", null, owner.account.token)).size()).isZero();
        JsonNode updated = node(success("PUT", path, edit(0, propertyDetails("Renamed home")), owner.account.token, 200));
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(updated.get("createdAt")).isEqualTo(property.get("createdAt"));
        JsonNode active = node(success("PATCH", path + "/status", status(1, "ACTIVE"), owner.account.token, 200));
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(node(call("GET", owner.base() + "?status=ACTIVE&limit=1&offset=0", null, owner.account.token)).size()).isEqualTo(1);
        assertThat(node(call("GET", owner.base() + "?offset=1", null, owner.account.token)).size()).isZero();
    }

    @Test
    void unrelatedOrganizationsCannotReadOrMutatePropertiesOrUnits() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Portfolio outsider = portfolio("AGENCY");
        JsonNode property = createProperty(owner, propertyDetails("Private home"));
        String id = property.get("id").asText();
        String path = owner.base() + "/" + id;
        JsonNode unit = node(success("POST", path + "/units", unitDetails("A-1"), owner.account.token, 201));
        for (String base : new String[]{owner.base(), outsider.base()}) {
            String foreign = base + "/" + id;
            success("GET", foreign, null, outsider.account.token, 404);
            success("PUT", foreign, edit(0, propertyDetails("Stolen home")), outsider.account.token, 404);
            success("GET", foreign + "/units", null, outsider.account.token, 404);
            success("POST", foreign + "/units", unitDetails("Intruder"), outsider.account.token, 404);
            success("PUT", foreign + "/units/" + unit.get("id").asText(), edit(0, unitDetails("Intruder")), outsider.account.token, 404);
            success("PATCH", foreign + "/status", status(0, "ARCHIVED"), outsider.account.token, 404);
            success("PATCH", foreign + "/management", management(0, outsider.organization), outsider.account.token, 404);
        }
        assertThat(node(success("GET", outsider.base(), null, outsider.account.token, 200)).size()).isZero();
        assertThat(node(success("GET", path, null, owner.account.token, 200)).get("name").asText()).isEqualTo("Private home");
        jdbc.update("UPDATE app_users SET platform_role = 'PLATFORM_ADMIN' WHERE id = ?", outsider.account.id);
        success("GET", path, null, outsider.account.token, 404);
    }

    @Test
    void ordinaryMembersCanReadButOnlyOrganizationManagersCanWrite() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Account colleague = account();
        addMember(owner, colleague, "MEMBER");
        JsonNode property = createProperty(owner, propertyDetails("Owner home"));
        String path = owner.base() + "/" + property.get("id").asText();
        JsonNode unit = node(success("POST", path + "/units", unitDetails("1"), owner.account.token, 201));
        String unitPath = path + "/units/" + unit.get("id").asText();
        success("GET", path, null, colleague.token, 200);
        success("GET", unitPath, null, colleague.token, 200);
        success("POST", owner.base(), propertyDetails("Denied"), colleague.token, 403);
        success("PUT", path, edit(0, propertyDetails("Denied")), colleague.token, 403);
        success("POST", path + "/units", unitDetails("Denied"), colleague.token, 403);
        success("PUT", unitPath, edit(0, unitDetails("Denied")), colleague.token, 403);
        success("PATCH", unitPath + "/status", status(0, "ARCHIVED"), colleague.token, 403);
        success("PATCH", path + "/status", status(0, "ARCHIVED"), colleague.token, 403);
        success("PATCH", path + "/management", management(0, null), colleague.token, 403);
        success("PATCH", "/api/v1/organizations/" + owner.organization + "/members/" + colleague.id,
                Map.of("role", "ADMIN"), owner.account.token, 200);
        success("PUT", path, edit(0, propertyDetails("Administrator edit")), colleague.token, 200);
        success("PUT", unitPath, edit(0, unitDetails("Administrator unit")), colleague.token, 200);
    }

    @Test
    void ownerDelegationSharesOnlyTheSelectedPropertyAndRevocationIsImmediate() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Portfolio agency = portfolio("AGENCY");
        JsonNode property = createProperty(owner, propertyDetails("Managed home"));
        JsonNode privateProperty = createProperty(owner, propertyDetails("Other home"));
        String id = property.get("id").asText();
        String ownerPath = owner.base() + "/" + id;
        String managerPath = agency.base() + "/" + id;
        success("GET", managerPath, null, agency.account.token, 404);
        JsonNode delegated = node(success("PATCH", ownerPath + "/management", management(0, agency.organization), owner.account.token, 200));
        assertThat(delegated.get("ownerOrganizationId").asText()).isEqualTo(owner.organization);
        assertThat(delegated.get("managerOrganizationId").asText()).isEqualTo(agency.organization);
        assertThat(node(success("GET", agency.base(), null, agency.account.token, 200)).size()).isEqualTo(1);
        success("GET", agency.base() + "/" + privateProperty.get("id").asText(), null, agency.account.token, 404);
        success("GET", "/api/v1/organizations/" + owner.organization + "/members", null, agency.account.token, 404);
        success("PUT", managerPath, edit(1, propertyDetails("Agency edit")), agency.account.token, 200);
        JsonNode unit = node(success("POST", managerPath + "/units", unitDetails("Manager-1"), agency.account.token, 201));
        success("PUT", managerPath + "/units/" + unit.get("id").asText(), edit(0, unitDetails("Manager-2")), agency.account.token, 200);
        success("PATCH", managerPath + "/status", status(2, "ARCHIVED"), agency.account.token, 403);
        success("PATCH", managerPath + "/management", management(2, null), agency.account.token, 403);
        JsonNode revoked = node(success("PATCH", ownerPath + "/management", management(2, null), owner.account.token, 200));
        assertThat(revoked.get("ownerOrganizationId").asText()).isEqualTo(owner.organization);
        assertThat(revoked.get("managerOrganizationId").isNull()).isTrue();
        success("GET", managerPath, null, agency.account.token, 404);
        success("PUT", managerPath, edit(3, propertyDetails("Revoked edit")), agency.account.token, 404);
        success("GET", managerPath + "/units/" + unit.get("id").asText(), null, agency.account.token, 404);
        assertThat(node(success("GET", agency.base(), null, agency.account.token, 200)).size()).isZero();
    }

    @Test
    void serviceProvidersAndInvalidAgencyAssignmentsDoNotGainPropertyManagement() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Portfolio provider = portfolio("SERVICE_PROVIDER");
        success("POST", provider.base(), propertyDetails("Not a landlord"), provider.account.token, 403);
        String path = owner.base() + "/" + createProperty(owner, propertyDetails("Home")).get("id").asText();
        for (String target : new String[]{owner.organization, provider.organization, UUID.randomUUID().toString()}) {
            success("PATCH", path + "/management", management(0, target), owner.account.token, 400);
        }
        assertThat(node(success("GET", path, null, owner.account.token, 200)).get("version").asLong()).isZero();
    }

    @Test
    void concurrentPropertyEditsCannotSilentlyOverwriteEachOther() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        String path = owner.base() + "/" + createProperty(owner, propertyDetails("Home")).get("id").asText();
        var first = async("PUT", path, edit(0, propertyDetails("First edit")), owner.account.token);
        var second = async("PUT", path, edit(0, propertyDetails("Second edit")), owner.account.token);
        assertThat(new int[]{first.get(15, TimeUnit.SECONDS).statusCode(), second.get(15, TimeUnit.SECONDS).statusCode()})
                .containsExactlyInAnyOrder(200, 409);
        assertThat(node(success("GET", path, null, owner.account.token, 200)).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void unitPricingUsesDecimalsAndStaleEditsAreRejected() throws Exception {
        Portfolio owner = portfolio("AGENCY");
        String path = owner.base() + "/" + createProperty(owner, propertyDetails("Agency owned home")).get("id").asText();
        JsonNode unit = node(success("POST", path + "/units", unitDetails(" A-01 "), owner.account.token, 201));
        String unitPath = path + "/units/" + unit.get("id").asText();
        assertThat(unit.get("label").asText()).isEqualTo("A-01");
        assertThat(unit.get("currency").asText()).isEqualTo("KES");
        assertThat(unit.get("monthlyRent").decimalValue()).isEqualByComparingTo("25000.50");
        assertThat(unit.get("deposit").decimalValue()).isEqualByComparingTo("50000.25");
        Map<String, Object> changed = unitDetails("A-01");
        changed.put("availability", "RESERVED");
        changed.put("monthlyRent", "26000.75");
        var first = async("PUT", unitPath, edit(0, changed), owner.account.token);
        var second = async("PUT", unitPath, edit(0, unitDetails("A-02")), owner.account.token);
        assertThat(new int[]{first.get(15, TimeUnit.SECONDS).statusCode(), second.get(15, TimeUnit.SECONDS).statusCode()})
                .containsExactlyInAnyOrder(200, 409);
        assertThat(node(success("GET", unitPath, null, owner.account.token, 200)).get("version").asLong()).isEqualTo(1);
        success("PUT", unitPath, edit(1, changed), owner.account.token, 200);
        assertThat(node(success("GET", path + "/units?availability=RESERVED", null, owner.account.token, 200)).size()).isEqualTo(1);
        assertThat(node(success("GET", path + "/units?availability=AVAILABLE", null, owner.account.token, 200)).size()).isZero();
    }

    @Test
    void archivalRetainsRecordsFreezesEditsAndSupportsRestoration() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        JsonNode property = createProperty(owner, propertyDetails("Home"));
        String path = owner.base() + "/" + property.get("id").asText();
        JsonNode unit = node(success("POST", path + "/units", unitDetails("A-1"), owner.account.token, 201));
        String unitPath = path + "/units/" + unit.get("id").asText();
        success("PATCH", unitPath + "/status", status(0, "ARCHIVED"), owner.account.token, 200);
        assertThat(node(success("GET", path + "/units", null, owner.account.token, 200)).size()).isZero();
        assertThat(node(success("GET", path + "/units?includeArchived=true", null, owner.account.token, 200)).size()).isEqualTo(1);
        success("POST", path + "/units", unitDetails("a-1"), owner.account.token, 409);
        success("PUT", unitPath, edit(1, unitDetails("A-1")), owner.account.token, 409);
        success("PATCH", path + "/status", status(0, "ARCHIVED"), owner.account.token, 200);
        assertThat(node(success("GET", owner.base(), null, owner.account.token, 200)).size()).isZero();
        assertThat(node(success("GET", owner.base() + "?status=ARCHIVED", null, owner.account.token, 200)).size()).isEqualTo(1);
        success("GET", path, null, owner.account.token, 200);
        success("PUT", path, edit(1, propertyDetails("Frozen")), owner.account.token, 409);
        success("POST", path + "/units", unitDetails("New unit"), owner.account.token, 409);
        success("PUT", unitPath, edit(1, unitDetails("Frozen unit")), owner.account.token, 409);
        success("PATCH", unitPath + "/status", status(1, "ACTIVE"), owner.account.token, 409);
        success("PATCH", path + "/status", status(1, "ACTIVE"), owner.account.token, 200);
        success("PATCH", unitPath + "/status", status(1, "ACTIVE"), owner.account.token, 200);
        assertThat(node(success("GET", path + "/units", null, owner.account.token, 200)).size()).isEqualTo(1);
        assertThat(node(success("GET", unitPath, null, owner.account.token, 200)).get("version").asLong()).isEqualTo(2);
    }

    @Test
    void agencyAccessCanBeRevokedEvenWhenThePropertyIsArchived() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Portfolio agency = portfolio("AGENCY");
        String id = createProperty(owner, propertyDetails("Home")).get("id").asText();
        String path = owner.base() + "/" + id;
        success("PATCH", path + "/management", management(0, agency.organization), owner.account.token, 200);
        success("PATCH", path + "/status", status(1, "ARCHIVED"), owner.account.token, 200);
        success("PATCH", path + "/management", management(2, null), owner.account.token, 200);
        success("GET", agency.base() + "/" + id, null, agency.account.token, 404);
        success("PATCH", path + "/management", management(3, agency.organization), owner.account.token, 409);
    }

    @Test
    void unitsCannotBeAddressedThroughAnotherPropertyAndDuplicateLabelsRollback() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        String first = owner.base() + "/" + createProperty(owner, propertyDetails("First")).get("id").asText();
        String second = owner.base() + "/" + createProperty(owner, propertyDetails("Second")).get("id").asText();
        JsonNode unit = node(success("POST", first + "/units", unitDetails("A-01"), owner.account.token, 201));
        String wrong = second + "/units/" + unit.get("id").asText();
        success("GET", wrong, null, owner.account.token, 404);
        success("PUT", wrong, edit(0, unitDetails("Hijacked")), owner.account.token, 404);
        success("PATCH", wrong + "/status", status(0, "ARCHIVED"), owner.account.token, 404);
        success("POST", first + "/units", unitDetails("a-01"), owner.account.token, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action LIKE 'UNIT_%'",
                Integer.class, UUID.fromString(unit.get("id").asText()))).isEqualTo(1);
        assertThat(node(success("GET", first + "/units", null, owner.account.token, 200)).size()).isEqualTo(1);
    }

    @Test
    void membershipRevocationRemovesAccessWithAnExistingToken() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        Account admin = account();
        addMember(owner, admin, "ADMIN");
        String path = owner.base() + "/" + createProperty(owner, propertyDetails("Home")).get("id").asText();
        success("GET", path, null, admin.token, 200);
        success("DELETE", "/api/v1/organizations/" + owner.organization + "/members/" + admin.id, null, owner.account.token, 204);
        success("GET", path, null, admin.token, 404);
        success("PUT", path, edit(0, propertyDetails("Revoked edit")), admin.token, 404);
        success("POST", path + "/units", unitDetails("Revoked unit"), admin.token, 404);
    }

    @Test
    void structuredAuditSnapshotsContainPreviousAndNewValues() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        JsonNode property = createProperty(owner, propertyDetails("Original"));
        UUID propertyId = UUID.fromString(property.get("id").asText());
        String path = owner.base() + "/" + propertyId;
        success("PUT", path, edit(0, propertyDetails("Updated")), owner.account.token, 200);
        String propertyAudit = jdbc.queryForObject("SELECT changes::text FROM audit_events WHERE entity_id = ? AND action = 'PROPERTY_UPDATED'", String.class, propertyId);
        JsonNode values = json.readTree(propertyAudit);
        assertThat(values.get("previousValue").get("name").asText()).isEqualTo("Original");
        assertThat(values.get("newValue").get("name").asText()).isEqualTo("Updated");
        JsonNode unit = node(success("POST", path + "/units", unitDetails("A"), owner.account.token, 201));
        String unitAudit = jdbc.queryForObject("SELECT changes::text FROM audit_events WHERE entity_id = ? AND action = 'UNIT_CREATED'",
                String.class, UUID.fromString(unit.get("id").asText()));
        JsonNode created = json.readTree(unitAudit);
        assertThat(created.get("previousValue").isNull()).isTrue();
        assertThat(created.get("newValue").get("monthlyRent").decimalValue()).isEqualByComparingTo("25000.50");
        assertThat(jdbc.queryForObject("SELECT organization_id::text FROM audit_events WHERE entity_id = ? AND action = 'UNIT_CREATED'",
                String.class, UUID.fromString(unit.get("id").asText()))).isEqualTo(owner.organization);
    }

    @Test
    void invalidInputAndPaginationAreRejectedAndApisRequireAuthentication() throws Exception {
        Portfolio owner = portfolio("LANDLORD");
        success("GET", owner.base(), null, null, 401);
        success("POST", owner.base(), propertyDetails("Anonymous"), null, 401);
        for (Map.Entry<String, Object> field : Map.<String, Object>of(
                "name", " ", "countryCode", "ZZ", "type", "UNKNOWN", "latitude", "91", "longitude", "181").entrySet()) {
            var invalid = propertyDetails("Invalid"); invalid.put(field.getKey(), field.getValue());
            success("POST", owner.base(), invalid, owner.account.token, 400);
        }
        var incomplete = propertyDetails("Missing coordinate"); incomplete.put("latitude", "-1.2");
        success("POST", owner.base(), incomplete, owner.account.token, 400);
        String path = owner.base() + "/" + createProperty(owner, propertyDetails("Valid")).get("id").asText();
        success("PUT", path, Map.of("details", propertyDetails("Missing version")), owner.account.token, 400);
        success("PUT", path, edit(-1, propertyDetails("Negative version")), owner.account.token, 400);
        success("GET", owner.base() + "?limit=101", null, owner.account.token, 400);
        success("GET", owner.base() + "?offset=-1", null, owner.account.token, 400);
        success("GET", owner.base() + "?status=UNKNOWN", null, owner.account.token, 400);
        success("GET", path + "/units?offset=-1", null, owner.account.token, 400);
        for (Map.Entry<String, Object> field : Map.<String, Object>of(
                "currency", "ZZZ", "monthlyRent", "-1", "deposit", "0.001", "floorAreaSqm", "0",
                "bedrooms", -1, "availability", "UNKNOWN").entrySet()) {
            var invalid = unitDetails("Invalid"); invalid.put(field.getKey(), field.getValue());
            success("POST", path + "/units", invalid, owner.account.token, 400);
        }
        var coordinates = propertyDetails("Coordinates"); coordinates.put("latitude", "-1.2345678"); coordinates.put("longitude", "36.1234567");
        JsonNode precise = createProperty(owner, coordinates);
        assertThat(precise.get("latitude").decimalValue()).isEqualByComparingTo("-1.2345678");
    }

    @Test
    void frontendCanPreflightPutRequestsFromAnAllowedOrigin() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/organizations/" + UUID.randomUUID() + "/properties/" + UUID.randomUUID()))
                .timeout(Duration.ofSeconds(10)).header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PUT").header("Access-Control-Request-Headers", "Authorization,Content-Type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PUT");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin").orElse("")).isEqualTo("http://localhost:5173");
        var deniedRequest = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(10))
                .header("Origin", "https://untrusted.example.com").header("Access-Control-Request-Method", "PUT")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var denied = client.send(deniedRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private Portfolio portfolio(String type) throws Exception {
        Account actor = account();
        JsonNode organization = node(success("POST", "/api/v1/organizations", Map.of("name", "Property test organization", "type", type), actor.token, 201));
        return new Portfolio(actor, organization.get("id").asText());
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode registered = node(success("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Property test"), null, 201));
        JsonNode loggedIn = node(success("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(registered.get("id").asText()), email, loggedIn.get("accessToken").asText());
    }

    private void addMember(Portfolio portfolio, Account member, String role) throws Exception {
        success("POST", "/api/v1/organizations/" + portfolio.organization + "/members", Map.of("email", member.email, "role", role), portfolio.account.token, 201);
    }

    private JsonNode createProperty(Portfolio portfolio, Object details) throws Exception {
        return node(success("POST", portfolio.base(), details, portfolio.account.token, 201));
    }

    private Map<String, Object> propertyDetails(String name) {
        return new HashMap<>(Map.of("name", name, "description", "A property management test", "type", "HOUSE",
                "countryCode", "ke", "city", "Nairobi", "address", "Test street 1"));
    }

    private Map<String, Object> unitDetails(String label) {
        return new HashMap<>(Map.of("label", label, "bedrooms", 2, "bathrooms", 1, "floorAreaSqm", "72.50",
                "monthlyRent", "25000.50", "deposit", "50000.25", "currency", "kes", "availability", "AVAILABLE"));
    }

    private Map<String, Object> edit(long version, Object details) { return Map.of("version", version, "details", details); }
    private Map<String, Object> status(long version, String status) { return Map.of("version", version, "status", status); }
    private Map<String, Object> management(long version, String organization) {
        Map<String, Object> result = new HashMap<>(); result.put("version", version); result.put("managerOrganizationId", organization); return result;
    }

    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    private HttpResponse<String> success(String method, String path, Object body, String token, int status) throws Exception {
        var response = call(method, path, body, token);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return response;
    }

    private HttpRequest request(String method, String path, Object body, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        return client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, Object body, String token) {
        return client.sendAsync(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private record Account(UUID id, String email, String token) { }
    private record Portfolio(Account account, String organization) {
        String base() { return "/api/v1/organizations/" + organization + "/properties"; }
    }
}
