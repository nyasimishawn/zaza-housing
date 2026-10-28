package com.zaza.housing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
class PublicListingTests {
    private static final String PASSWORD = "Public-listing-test-password-42";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void draftsArePrivateAndAnonymousAccessOnlyIncludesGuestReads() throws Exception {
        Inventory inventory = inventory();
        JsonNode listing = draft(inventory, false);
        String id = listing.get("id").asText();
        success("GET", publicPath(id), null, null, 404);
        assertThat(search(inventory.tag, "").get("items").size()).isZero();
        success("GET", inventory.listings(), null, null, 401);
        success("POST", inventory.listings(), Map.of("unitId", inventory.unit, "details", details(inventory.tag, false)), null, 401);
        success("POST", inventory.listings() + "/" + id + "/publish", publication(0, 1, 0), null, 401);
        success("POST", "/api/v1/listings", Map.of(), null, 401);
        success("GET", "/api/v1/listings/not-a-uuid", null, null, 400);
        assertThat(listing.get("status").asText()).isEqualTo("DRAFT");
        assertThat(listing.get("publiclyVisible").asBoolean()).isFalse();
    }

    @Test
    void publishingExposesOnlyAnApprovedPublicProjection() throws Exception {
        Inventory inventory = inventory();
        JsonNode listing = draft(inventory, false);
        String id = listing.get("id").asText();
        JsonNode published = node(success("POST", inventory.listings() + "/" + id + "/publish", publication(0, 1, 0), inventory.owner.token, 200));
        assertThat(published.get("publiclyVisible").asBoolean()).isTrue();
        var response = success("GET", publicPath(id), null, null, 200);
        JsonNode visible = node(response);
        assertThat(visible.get("monthlyRent").decimalValue()).isEqualByComparingTo("25000.50");
        assertThat(visible.get("address").isNull()).isTrue();
        assertThat(visible.get("latitude").isNull()).isTrue();
        assertThat(visible.get("longitude").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("PRIVATE_ADDRESS", "PRIVATE_PROPERTY_NAME", "PRIVATE_PROPERTY_DESCRIPTION", "PRIVATE_UNIT_LABEL",
                inventory.property, inventory.unit, inventory.organization, inventory.owner.id.toString(), inventory.owner.email,
                "propertyId", "unitId", "ownerOrganizationId", "publisherOrganizationId", "createdBy", "password", "token", "version");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var results = success("GET", "/api/v1/listings?q=" + encode(inventory.tag), null, null, 200);
        assertThat(node(results).get("items").size()).isEqualTo(1);
        assertThat(results.body()).doesNotContain("PRIVATE_ADDRESS", inventory.organization, inventory.unit);
        assertThat(results.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void editsWithdrawAndExactLocationRequiresAnExplicitOptInAndRepublish() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText();
        publish(inventory, id, 0, 1, 0);
        String path = inventory.listings() + "/" + id;
        JsonNode edited = node(success("PUT", path, Map.of("version", 1, "details", details(inventory.tag, true)), inventory.owner.token, 200));
        assertThat(edited.get("status").asText()).isEqualTo("WITHDRAWN");
        success("GET", publicPath(id), null, null, 404);
        publish(inventory, id, 2, 1, 0);
        JsonNode precise = node(success("GET", publicPath(id), null, null, 200));
        assertThat(precise.get("address").asText()).isEqualTo("PRIVATE_ADDRESS");
        assertThat(precise.get("latitude").decimalValue()).isEqualByComparingTo("-1.2921000");
        assertThat(precise.get("longitude").decimalValue()).isEqualByComparingTo("36.8219000");
        success("PUT", path, Map.of("version", 3, "details", details(inventory.tag, false)), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        publish(inventory, id, 4, 1, 0);
        assertThat(node(success("GET", publicPath(id), null, null, 200)).get("address").isNull()).isTrue();
    }

    @Test
    void unavailableUnitsCannotPublishAndBecomingAvailableRequiresReview() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText();
        publish(inventory, id, 0, 1, 0);
        Map<String, Object> reserved = new HashMap<>(unitDetails("PRIVATE_UNIT_LABEL", "25000.50", "KES")); reserved.put("availability", "RESERVED");
        success("PUT", inventory.unitPath(), edit(0, reserved), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        success("POST", inventory.listings() + "/" + id + "/publish", publication(1, 1, 1), inventory.owner.token, 409);
        success("PUT", inventory.unitPath(), edit(1, unitDetails("PRIVATE_UNIT_LABEL", "25000.50", "KES")), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        publish(inventory, id, 1, 1, 2);
        success("GET", publicPath(id), null, null, 200);
    }

    @Test
    void priceChangesHideStaleOffersUntilSourceVersionsAreReviewed() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText();
        publish(inventory, id, 0, 1, 0);
        success("PUT", inventory.unitPath(), edit(0, unitDetails("PRIVATE_UNIT_LABEL", "27000.75", "KES")), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        assertThat(search(inventory.tag, "").get("items").size()).isZero();
        JsonNode hidden = node(success("GET", inventory.listings() + "/" + id, null, inventory.owner.token, 200));
        assertThat(hidden.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(hidden.get("publiclyVisible").asBoolean()).isFalse();
        success("POST", inventory.listings() + "/" + id + "/publish", publication(1, 1, 0), inventory.owner.token, 409);
        publish(inventory, id, 1, 1, 1);
        assertThat(node(success("GET", publicPath(id), null, null, 200)).get("monthlyRent").decimalValue()).isEqualByComparingTo("27000.75");
    }

    @Test
    void propertyAndUnitArchivalHideListingsAndRestorationDoesNotRepublish() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText();
        publish(inventory, id, 0, 1, 0);
        success("PATCH", inventory.unitPath() + "/status", status(0, "ARCHIVED"), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        success("POST", inventory.listings() + "/" + id + "/publish", publication(1, 1, 1), inventory.owner.token, 409);
        success("PATCH", inventory.unitPath() + "/status", status(1, "ACTIVE"), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        publish(inventory, id, 1, 1, 2);
        success("PATCH", inventory.propertyPath() + "/status", status(1, "ARCHIVED"), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        success("POST", inventory.listings() + "/" + id + "/withdraw", Map.of("version", 2), inventory.owner.token, 200);
        success("PATCH", inventory.propertyPath() + "/status", status(2, "ACTIVE"), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        publish(inventory, id, 3, 3, 2);
        success("GET", publicPath(id), null, null, 200);
    }

    @Test
    void draftPropertiesCannotPublishAndMissingOrStaleSourceVersionsAreRejected() throws Exception {
        Inventory inventory = inventory();
        success("PATCH", inventory.propertyPath() + "/status", status(1, "DRAFT"), inventory.owner.token, 200);
        String id = draft(inventory, false).get("id").asText();
        success("POST", inventory.listings() + "/" + id + "/publish", publication(0, 2, 0), inventory.owner.token, 409);
        success("PATCH", inventory.propertyPath() + "/status", status(2, "ACTIVE"), inventory.owner.token, 200);
        success("POST", inventory.listings() + "/" + id + "/publish", publication(0, 2, 0), inventory.owner.token, 409);
        success("POST", inventory.listings() + "/" + id + "/publish", Map.of("version", 0), inventory.owner.token, 400);
        publish(inventory, id, 0, 3, 0);
        success("POST", inventory.listings() + "/" + id + "/withdraw", Map.of("version", 0), inventory.owner.token, 409);
        success("POST", inventory.listings() + "/" + id + "/withdraw", Map.of("version", 1), inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
    }

    @Test
    void delegationAllowsAgencyPublicationAndRevocationHidesItsPublication() throws Exception {
        Inventory inventory = inventory();
        Account agency = account();
        String agencyOrg = organization(agency, "AGENCY");
        success("PATCH", inventory.propertyPath() + "/management", Map.of("version", 1, "managerOrganizationId", agencyOrg), inventory.owner.token, 200);
        String agencyBase = "/api/v1/organizations/" + agencyOrg + "/properties/" + inventory.property + "/listings";
        String id = node(success("POST", agencyBase, Map.of("unitId", inventory.unit, "details", details(inventory.tag, false)), agency.token, 201)).get("id").asText();
        JsonNode published = node(success("POST", agencyBase + "/" + id + "/publish", publication(0, 2, 0), agency.token, 200));
        assertThat(published.get("publisherOrganizationId").asText()).isEqualTo(agencyOrg);
        success("GET", publicPath(id), null, null, 200);
        Map<String, Object> revoke = new HashMap<>(); revoke.put("version", 2); revoke.put("managerOrganizationId", null);
        success("PATCH", inventory.propertyPath() + "/management", revoke, inventory.owner.token, 200);
        success("GET", publicPath(id), null, null, 404);
        success("GET", agencyBase + "/" + id, null, agency.token, 404);
        success("POST", agencyBase + "/" + id + "/publish", publication(1, 3, 0), agency.token, 404);
        publish(inventory, id, 1, 3, 0);
        JsonNode takeover = node(success("GET", inventory.listings() + "/" + id, null, inventory.owner.token, 200));
        assertThat(takeover.get("publisherOrganizationId").asText()).isEqualTo(inventory.organization);
        success("GET", publicPath(id), null, null, 200);
    }

    @Test
    void listingManagementEnforcesMembershipRolesAndImmediateRevocation() throws Exception {
        Inventory inventory = inventory();
        Account member = account();
        String members = "/api/v1/organizations/" + inventory.organization + "/members";
        success("POST", members, Map.of("email", member.email, "role", "MEMBER"), inventory.owner.token, 201);
        String id = draft(inventory, false).get("id").asText();
        String path = inventory.listings() + "/" + id;
        success("GET", path, null, member.token, 404);
        success("PUT", path, edit(0, details(inventory.tag, true)), member.token, 403);
        success("POST", path + "/publish", publication(0, 1, 0), member.token, 403);
        success("POST", path + "/withdraw", Map.of("version", 0), member.token, 403);
        success("PATCH", members + "/" + member.id, Map.of("role", "ADMIN"), inventory.owner.token, 200);
        success("POST", path + "/publish", publication(0, 1, 0), member.token, 200);
        success("DELETE", members + "/" + member.id, null, inventory.owner.token, 204);
        success("GET", path, null, member.token, 404);
        success("POST", path + "/withdraw", Map.of("version", 1), member.token, 404);
        success("GET", publicPath(id), null, null, 200);
    }

    @Test
    void crossOrganizationAndWrongParentPathsCannotExposeOrMutateListings() throws Exception {
        Inventory first = inventory();
        Inventory second = inventory();
        String id = draft(first, false).get("id").asText();
        success("GET", first.listings() + "/" + id, null, second.owner.token, 404);
        success("GET", second.listings() + "/" + id, null, second.owner.token, 404);
        success("POST", second.listings(), Map.of("unitId", first.unit, "details", details(second.tag, false)), second.owner.token, 404);
        success("PUT", second.listings() + "/" + id, edit(0, details(second.tag, true)), second.owner.token, 404);
        success("POST", second.listings() + "/" + id + "/publish", publication(0, 1, 0), second.owner.token, 404);
        success("POST", first.listings() + "/" + id + "/withdraw", Map.of("version", 0), second.owner.token, 404);
        success("POST", first.listings(), Map.of("unitId", first.unit, "details", details(first.tag, false)), first.owner.token, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action LIKE 'LISTING_%'", Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void searchFiltersPricesBedroomsLocationAndTypeWithDeterministicPagination() throws Exception {
        Inventory inventory = inventory();
        String initial = draft(inventory, false).get("id").asText(); publish(inventory, initial, 0, 1, 0);
        String cheap = extraListing(inventory, "Cheap", "10000.25", "KES");
        String expensive = extraListing(inventory, "Expensive", "40000.75", "KES");
        extraListing(inventory, "Foreign currency", "100.00", "USD");
        JsonNode filtered = search(inventory.tag, "&countryCode=ke&city=nairobi&type=HOUSE&currency=kes&minBedrooms=2&maxBedrooms=2&minRent=10000.25&maxRent=40000.75&sort=PRICE_ASC&limit=2");
        assertThat(filtered.get("items").size()).isEqualTo(2);
        assertThat(filtered.get("items").get(0).get("id").asText()).isEqualTo(cheap);
        assertThat(filtered.get("items").get(1).get("id").asText()).isEqualTo(initial);
        assertThat(filtered.get("hasMore").asBoolean()).isTrue();
        JsonNode next = search(inventory.tag, "&currency=KES&sort=PRICE_ASC&limit=2&offset=2");
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(next.get("items").get(0).get("id").asText()).isEqualTo(expensive);
        assertThat(next.get("hasMore").asBoolean()).isFalse();
        assertThat(search(inventory.tag, "&currency=KES&sort=PRICE_DESC").get("items").get(0).get("id").asText()).isEqualTo(expensive);
        assertThat(search(inventory.tag, "&type=LAND").get("items").size()).isZero();
        assertThat(search(inventory.tag, "&currency=USD").get("items").size()).isEqualTo(1);
        assertThat(search(inventory.tag, "&city=Mombasa").get("items").size()).isZero();
    }

    @Test
    void searchTextUsesSafeFullTextQueriesAndNotSqlOrWildcardInput() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText(); publish(inventory, id, 0, 1, 0);
        assertThat(search(inventory.tag + " Riverside", "").get("items").size()).isEqualTo(1);
        assertThat(search(inventory.tag + " nonexistentword", "").get("items").size()).isZero();
        assertThat(search(inventory.tag + " ' OR 1=1 --", "").get("items").size()).isZero();
        assertThat(search("%%", "").get("items").size()).isZero();
        assertThat(search(inventory.tag.toUpperCase(), "").get("items").size()).isEqualTo(1);
    }

    @Test
    void invalidSearchParametersReturnControlledBadRequests() throws Exception {
        for (String query : new String[]{"limit=0", "limit=101", "offset=-1", "offset=10001", "offset=abc", "q=x", "q=%20%20",
                "countryCode=ZZ", "currency=ZZZ", "city=%20", "minRent=-1", "minRent=0.001&currency=KES", "minRent=100",
                "sort=PRICE_ASC", "sort=UNKNOWN", "type=UNKNOWN", "minBedrooms=-1", "maxBedrooms=101",
                "minBedrooms=3&maxBedrooms=2", "minRent=200&maxRent=100&currency=KES"}) {
            var response = success("GET", "/api/v1/listings?" + query, null, null, 400);
            assertThat(response.body()).doesNotContain("stackTrace", "JdbcTemplate", "SELECT ", "password");
        }
    }

    @Test
    void concurrentPublicationRejectsOneStaleWriterAndAuditsOnlySuccessfulPublication() throws Exception {
        Inventory inventory = inventory();
        String id = draft(inventory, false).get("id").asText();
        String path = inventory.listings() + "/" + id + "/publish";
        var first = async("POST", path, publication(0, 1, 0), inventory.owner.token);
        var second = async("POST", path, publication(0, 1, 0), inventory.owner.token);
        assertThat(new int[]{first.get(15, TimeUnit.SECONDS).statusCode(), second.get(15, TimeUnit.SECONDS).statusCode()}).containsExactlyInAnyOrder(200, 409);
        JsonNode changes = json.readTree(jdbc.queryForObject("SELECT changes::text FROM audit_events WHERE entity_id = ? AND action = 'LISTING_PUBLISHED'", String.class, UUID.fromString(id)));
        assertThat(changes.get("previousValue").get("status").asText()).isEqualTo("DRAFT");
        assertThat(changes.get("newValue").get("publicSnapshot").get("monthlyRent").decimalValue()).isEqualByComparingTo("25000.50");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'LISTING_PUBLISHED'", Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void invalidListingInputCannotGrantPublicationOrAlterSourceRelationships() throws Exception {
        Inventory inventory = inventory();
        success("POST", inventory.listings(), Map.of("unitId", inventory.unit, "details", Map.of("title", "Valid", "description", "Description")), inventory.owner.token, 400);
        success("POST", inventory.listings(), Map.of("unitId", inventory.unit, "details", details(" ", false)), inventory.owner.token, 400);
        var body = new HashMap<String, Object>(); body.put("unitId", inventory.unit); body.put("details", details(inventory.tag, false));
        body.put("status", "PUBLISHED"); body.put("publiclyVisible", true); body.put("publisherOrganizationId", inventory.organization);
        JsonNode listing = node(success("POST", inventory.listings(), body, inventory.owner.token, 201));
        assertThat(listing.get("status").asText()).isEqualTo("DRAFT");
        assertThat(listing.get("publiclyVisible").asBoolean()).isFalse();
        assertThat(listing.get("publisherOrganizationId").isNull()).isTrue();
        success("GET", publicPath(listing.get("id").asText()), null, null, 404);
        success("PUT", inventory.listings() + "/" + listing.get("id").asText(), edit(-1, details(inventory.tag, false)), inventory.owner.token, 400);
    }

    private Inventory inventory() throws Exception {
        Account owner = account();
        String organization = organization(owner, "LANDLORD");
        String base = "/api/v1/organizations/" + organization + "/properties";
        String property = node(success("POST", base, Map.of("name", "PRIVATE_PROPERTY_NAME", "description", "PRIVATE_PROPERTY_DESCRIPTION",
                "type", "HOUSE", "countryCode", "KE", "city", "Nairobi", "address", "PRIVATE_ADDRESS", "latitude", "-1.2921", "longitude", "36.8219"), owner.token, 201)).get("id").asText();
        success("PATCH", base + "/" + property + "/status", status(0, "ACTIVE"), owner.token, 200);
        String unit = node(success("POST", base + "/" + property + "/units", unitDetails("PRIVATE_UNIT_LABEL", "25000.50", "KES"), owner.token, 201)).get("id").asText();
        return new Inventory(owner, organization, property, unit, "listing" + UUID.randomUUID().toString().replace("-", ""));
    }
    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode registered = node(success("POST", "/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Listing test"), null, 201));
        JsonNode login = node(success("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200));
        return new Account(UUID.fromString(registered.get("id").asText()), email, login.get("accessToken").asText());
    }
    private String organization(Account actor, String type) throws Exception {
        return node(success("POST", "/api/v1/organizations", Map.of("name", "Listing test organization", "type", type), actor.token, 201)).get("id").asText();
    }
    private JsonNode draft(Inventory inventory, boolean disclose) throws Exception {
        return node(success("POST", inventory.listings(), Map.of("unitId", inventory.unit, "details", details(inventory.tag, disclose)), inventory.owner.token, 201));
    }
    private void publish(Inventory inventory, String id, long version, long propertyVersion, long unitVersion) throws Exception {
        success("POST", inventory.listings() + "/" + id + "/publish", publication(version, propertyVersion, unitVersion), inventory.owner.token, 200);
    }
    private String extraListing(Inventory inventory, String label, String rent, String currency) throws Exception {
        String unit = node(success("POST", inventory.propertyPath() + "/units", unitDetails(label, rent, currency), inventory.owner.token, 201)).get("id").asText();
        String id = node(success("POST", inventory.listings(), Map.of("unitId", unit, "details", details(inventory.tag, false)), inventory.owner.token, 201)).get("id").asText();
        publish(inventory, id, 0, 1, 0); return id;
    }
    private Map<String, Object> details(String tag, boolean disclose) {
        return Map.of("title", tag.isBlank() ? tag : tag + " Riverside home", "description", "Bright rental near amenities", "showExactLocation", disclose);
    }
    private Map<String, Object> unitDetails(String label, String rent, String currency) {
        return Map.of("label", label, "bedrooms", 2, "bathrooms", 1, "floorAreaSqm", "72.50", "monthlyRent", rent, "deposit", "50000.25", "currency", currency, "availability", "AVAILABLE");
    }
    private Map<String, Object> edit(long version, Object details) { return Map.of("version", version, "details", details); }
    private Map<String, Object> status(long version, String status) { return Map.of("version", version, "status", status); }
    private Map<String, Object> publication(long version, long propertyVersion, long unitVersion) { return Map.of("version", version, "propertyVersion", propertyVersion, "unitVersion", unitVersion); }
    private String publicPath(String id) { return "/api/v1/listings/" + id; }
    private String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private JsonNode search(String q, String suffix) throws Exception { return node(success("GET", "/api/v1/listings?q=" + encode(q) + suffix, null, null, 200)); }
    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpRequest request(String method, String path, Object body, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private HttpResponse<String> success(String method, String path, Object body, String token, int expected) throws Exception {
        var response = client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expected); return response;
    }
    private CompletableFuture<HttpResponse<String>> async(String method, String path, Object body, String token) { return client.sendAsync(request(method, path, body, token), HttpResponse.BodyHandlers.ofString()); }
    private record Account(UUID id, String email, String token) { }
    private record Inventory(Account owner, String organization, String property, String unit, String tag) {
        String propertyPath() { return "/api/v1/organizations/" + organization + "/properties/" + property; }
        String unitPath() { return propertyPath() + "/units/" + unit; }
        String listings() { return propertyPath() + "/listings"; }
    }
}
