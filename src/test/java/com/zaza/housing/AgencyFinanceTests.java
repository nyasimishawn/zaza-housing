package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgencyFinanceTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired LeaseTestClock clock;

  @BeforeEach
  void resetClock() {
    clock.now =
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456789);
  }

  private String crm(Inventory i) {
    return "/api/v1/organizations/" + i.organization + "/agency";
  }

  private Map<String, Object> crmCommand(long version) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "version",
        version,
        "message",
        "Reviewed commission evidence and exact amount.",
        "consent",
        true,
        "password",
        PASSWORD);
  }

  private JsonNode qualified(Inventory i) throws Exception {
    String base = crm(i);
    String enquiry =
        jdbc.queryForObject(
            "SELECT id::text FROM conversations WHERE listing_id=? AND customer_id=?",
            String.class,
            UUID.fromString(i.listing),
            i.tenant.id);
    call(
        "POST",
        base + "/assignments",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            i.property,
            "agentId",
            i.owner.id,
            "active",
            true,
            "message",
            "Responsible converting agent."),
        i.owner,
        200);
    var client =
        node(
            call(
                "POST",
                base + "/clients",
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "enquiryId",
                    enquiry,
                    "details",
                    Map.of(
                        "fullName",
                        "Registered prospective tenant",
                        "countryCode",
                        "KE",
                        "city",
                        "Nairobi",
                        "currency",
                        "KES",
                        "requirements",
                        "An accessible home near transport.",
                        "consentToStore",
                        true)),
                i.owner,
                200));
    var lead =
        node(
            call(
                "POST",
                base + "/leads",
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "clientId",
                    client.get("id").asText(),
                    "title",
                    "Signed-deal pipeline",
                    "propertyId",
                    i.property,
                    "listingId",
                    i.listing,
                    "enquiryId",
                    enquiry,
                    "agentId",
                    i.owner.id),
                i.owner,
                200));
    return node(
        call(
            "POST",
            base + "/leads/" + lead.get("id").asText() + "/stage",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                0,
                "stage",
                "QUALIFIED",
                "message",
                "Needs and budget confirmed."),
            i.owner,
            200));
  }

  private JsonNode won(Inventory i) throws Exception {
    var lead = qualified(i);
    String path = crm(i) + "/leads/" + lead.get("id").asText();
    call(
        "POST",
        path + "/stage",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            1,
            "stage",
            "APPLICATION",
            "applicationId",
            i.application,
            "message",
            "Actual submitted application linked."),
        i.owner,
        200);
    call(
        "POST",
        path + "/stage",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            2,
            "stage",
            "WON",
            "leaseId",
            UUID.randomUUID(),
            "message",
            "Fake lease cannot convert this lead."),
        i.owner,
        400);
    var lease = executed(i);
    return node(
        call(
            "POST",
            path + "/stage",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                2,
                "stage",
                "WON",
                "leaseId",
                lease.get("id").asText(),
                "message",
                "Both parties signed the source lease."),
            i.owner,
            200));
  }

  @Test
  void realSignedDealDrivesCommissionIndependentApprovalExactPaymentAndReports() throws Exception {
    var i = inventory();
    var lead = won(i);
    assertThat(lead.get("wonAgentId").asText()).isEqualTo(i.owner.id.toString());
    var body =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "leadId",
                lead.get("id").asText(),
                "agentId",
                i.owner.id,
                "amount",
                "2500.55",
                "currency",
                "KES",
                "agreement",
                "Agreed fixed commission for this signed lease.",
                "consent",
                true));
    body.put("currency", "USD");
    call("POST", crm(i) + "/commissions", body, i.owner, 400);
    body.put("currency", "KES");
    var record = node(call("POST", crm(i) + "/commissions", body, i.owner, 200));
    String path = crm(i) + "/commissions/" + record.get("id").asText();
    call("POST", path + "/approve", crmCommand(0), i.owner, 403);
    var manager = account();
    call(
        "POST",
        "/api/v1/organizations/" + i.organization + "/members",
        Map.of("email", manager.email, "role", "ADMIN"),
        i.owner,
        201);
    call("POST", path + "/approve", crmCommand(0), manager, 200);
    var pay = new HashMap<>(crmCommand(1));
    pay.put("externalReference", "TRANSFER-" + UUID.randomUUID());
    pay.put("paidAt", clock.instant().toString());
    var paid = node(call("POST", path + "/pay", pay, manager, 200));
    call("POST", path + "/pay", pay, manager, 200);
    assertThat(Instant.parse(paid.get("approvedAt").asText()))
        .isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(Instant.parse(paid.get("paidAt").asText()))
        .isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    pay.put("externalReference", "DIFFERENT-REFERENCE");
    call("POST", path + "/pay", pay, manager, 409);
    body.put("requestId", UUID.randomUUID());
    call("POST", crm(i) + "/commissions", body, i.owner, 409);
    var report =
        node(
            call(
                "GET",
                crm(i) + "/reports?from=" + today().minusDays(1) + "&to=" + today().plusDays(1),
                null,
                manager,
                200));
    assertThat(report.get("dealsWon").asLong()).isEqualTo(1);
    assertThat(report.get("commissions").get(0).get("paid").decimalValue())
        .isEqualByComparingTo("2500.55");
    assertThat(node(call("GET", path + "/history", null, i.owner, 200)).size()).isEqualTo(3);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE agency_commissions SET amount=1 WHERE id=?", id(record)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void unsignedLeadsCurrencyMismatchAndUnrelatedAgentsCannotClaimCommission() throws Exception {
    var i = inventory();
    var lead = qualified(i);
    var body =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "leadId",
                lead.get("id").asText(),
                "agentId",
                i.owner.id,
                "amount",
                "100.00",
                "currency",
                "KES",
                "agreement",
                "Commission requires a real signed lease first.",
                "consent",
                true));
    call("POST", crm(i) + "/commissions", body, i.owner, 409);
    call("GET", crm(i) + "/reports?from=2027-01-10&to=2027-01-01", null, i.owner, 400);
    var outsider = account();
    call("GET", crm(i) + "/commissions", null, outsider, 404);
    var member = account();
    call(
        "POST",
        "/api/v1/organizations/" + i.organization + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        i.owner,
        201);
    var report =
        node(
            call(
                "GET",
                crm(i) + "/reports?from=" + today().minusDays(1) + "&to=" + today().plusDays(1),
                null,
                member,
                200));
    assertThat(report.get("leadsCreated").asLong()).isZero();
    assertThat(report.get("agents").size()).isEqualTo(1);
    assertThat(report.get("agents").get(0).get("id").asText()).isEqualTo(member.id.toString());
  }

  private JsonNode draft(Inventory inventory) throws Exception {
    return draft(inventory, terms());
  }

  private JsonNode draft(Inventory inventory, Map<String, Object> terms) throws Exception {
    return node(
        call(
            "POST",
            inventory.base(),
            Map.of("applicationId", inventory.application, "applicationVersion", 3, "terms", terms),
            inventory.owner,
            201));
  }

  private JsonNode send(Inventory inventory, JsonNode lease) throws Exception {
    return node(
        call("POST", inventory.path(lease) + "/send", version(lease), inventory.owner, 200));
  }

  private JsonNode sent(Inventory inventory) throws Exception {
    return send(inventory, draft(inventory));
  }

  private JsonNode executed(Inventory inventory) throws Exception {
    JsonNode lease = sent(inventory);
    lease = sign(inventory, lease, true);
    return sign(inventory, lease, false);
  }

  private JsonNode active(Inventory inventory) throws Exception {
    JsonNode lease = executed(inventory);
    return node(
        call(
            "POST",
            inventory.path(lease) + "/activate",
            Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
            inventory.owner,
            200));
  }

  private JsonNode sign(Inventory inventory, JsonNode lease, boolean tenant) throws Exception {
    return node(
        call(
            "POST",
            (tenant ? own(lease) : inventory.path(lease)) + "/sign",
            signBody(lease, tenant),
            tenant ? inventory.tenant : inventory.owner,
            200));
  }

  private JsonNode requestEnd(Inventory inventory, JsonNode lease, boolean tenant, LocalDate date)
      throws Exception {
    String path = tenant ? own(lease) : inventory.path(lease);
    var body = new HashMap<String, Object>();
    body.put("version", lease.get("version").asLong());
    body.put("proposedEndDate", date.toString());
    body.put("message", "Please end by mutual agreement.");
    JsonNode preview =
        node(
            call(
                "POST",
                path + "/terminations/preview",
                body,
                tenant ? inventory.tenant : inventory.owner,
                200));
    body.put(
        "evidence",
        evidence(
            lease,
            tenant,
            preview.get("documentHash").asText(),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
    return node(
        call(
            "POST",
            path + "/terminations",
            body,
            tenant ? inventory.tenant : inventory.owner,
            201));
  }

  private Map<String, Object> acceptBody(JsonNode lease, boolean tenant) {
    return Map.of(
        "version",
        lease.get("version").asLong(),
        "evidence",
        evidence(
            lease,
            tenant,
            lease.get("termination").get("documentHash").asText(),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
  }

  private Map<String, Object> signBody(JsonNode lease, boolean tenant) {
    return Map.of(
        "version",
        lease.get("version").asLong(),
        "revisionId",
        revision(lease),
        "evidence",
        evidence(
            lease,
            tenant,
            hash(lease),
            PASSWORD,
            tenant ? "Tenant Person" : "Lease Account",
            true));
  }

  private Map<String, Object> evidence(
      JsonNode lease, boolean tenant, String hash, String password, String name, boolean consent) {
    return Map.of(
        "documentHash", hash, "typedName", name, "password", password, "consent", consent);
  }

  private Map<String, Object> version(JsonNode lease) {
    return Map.of("version", lease.get("version").asLong());
  }

  private Map<String, Object> reason(JsonNode lease, String text) {
    return Map.of("version", lease.get("version").asLong(), "message", text);
  }

  private Map<String, Object> terms() {
    return Map.of(
        "title",
        "Residential agreement",
        "startDate",
        today().toString(),
        "endDate",
        today().plusDays(30).toString(),
        "monthlyRent",
        "25000",
        "deposit",
        "50000",
        "currency",
        "KES",
        "rentDueDay",
        5,
        "agreementText",
        "Negotiated agreement for caf\u00e9 premises.\n"
            + "Both parties must review this supplied agreement text.");
  }

  private String own(JsonNode lease) {
    return "/api/v1/me/leases/" + lease.get("id").asText();
  }

  private String revision(JsonNode lease) {
    return lease.get("revision").get("id").asText();
  }

  private String hash(JsonNode lease) {
    return lease.get("revision").get("documentHash").asText();
  }

  private UUID id(JsonNode node) {
    return UUID.fromString(node.get("id").asText());
  }

  private String constraint() {
    return "lease_test_" + UUID.randomUUID().toString().replace("-", "");
  }

  private JsonNode manager(Inventory inventory, JsonNode lease) throws Exception {
    return node(call("GET", inventory.path(lease), null, inventory.owner, 200));
  }

  private JsonNode tenant(Inventory inventory, JsonNode lease) throws Exception {
    return node(call("GET", own(lease), null, inventory.tenant, 200));
  }

  private JsonNode unit(Inventory inventory) throws Exception {
    return node(call("GET", inventory.unitPath(), null, inventory.owner, 200));
  }

  private JsonNode app(Inventory inventory) throws Exception {
    return node(
        call(
            "GET",
            inventory.propertyPath() + "/applications/" + inventory.application,
            null,
            inventory.owner,
            200));
  }

  private Map<String, Object> unitDetails() {
    return Map.of(
        "label",
        "A",
        "bedrooms",
        2,
        "bathrooms",
        1,
        "monthlyRent",
        "25000",
        "deposit",
        "50000",
        "currency",
        "KES",
        "availability",
        "AVAILABLE");
  }

  private Inventory inventory() throws Exception {
    Account owner = account();
    Account tenant = account();
    String organization = organization(owner, "AGENCY");
    call(
        "POST",
        "/api/v1/me/tenant-profile",
        Map.of(
            "legalName",
            "Tenant Person",
            "phone",
            "+254711111111",
            "countryCode",
            "KE",
            "city",
            "Nairobi",
            "employmentStatus",
            "EMPLOYED"),
        tenant,
        201);
    String base = "/api/v1/organizations/" + organization + "/properties";
    String property =
        node(call(
                "POST",
                base,
                Map.of(
                    "name",
                    "Lease property",
                    "type",
                    "HOUSE",
                    "countryCode",
                    "KE",
                    "city",
                    "Nairobi",
                    "address",
                    "Private lease premises"),
                owner,
                201))
            .get("id")
            .asText();
    String propertyPath = base + "/" + property;
    call("PATCH", propertyPath + "/status", Map.of("version", 0, "status", "ACTIVE"), owner, 200);
    String unit =
        node(call("POST", propertyPath + "/units", unitDetails(), owner, 201)).get("id").asText();
    String listing =
        node(call(
                "POST",
                propertyPath + "/listings",
                Map.of(
                    "unitId",
                    unit,
                    "details",
                    Map.of(
                        "title",
                        "Lease rental",
                        "description",
                        "Public rental offer",
                        "showExactLocation",
                        false)),
                owner,
                201))
            .get("id")
            .asText();
    call(
        "POST",
        propertyPath + "/listings/" + listing + "/publish",
        Map.of("version", 0, "propertyVersion", 1, "unitVersion", 0),
        owner,
        200);
    call(
        "POST",
        "/api/v1/listings/" + listing + "/enquiries",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "listingVersion",
            1,
            "message",
            "Interested in this home and a viewing."),
        tenant,
        200);
    String application =
        node(call(
                "POST",
                "/api/v1/me/applications",
                Map.of(
                    "listingId",
                    listing,
                    "listingVersion",
                    1,
                    "details",
                    Map.of(
                        "householdSize",
                        2,
                        "requestedMoveInDate",
                        today().plusDays(14).toString(),
                        "message",
                        "Interested")),
                tenant,
                201))
            .get("id")
            .asText();
    call(
        "POST",
        "/api/v1/me/applications/" + application + "/submit",
        Map.of("version", 0, "profileVersion", 0, "listingVersion", 1, "consentToShare", true),
        tenant,
        200);
    call(
        "POST",
        propertyPath + "/applications/" + application + "/review",
        Map.of("version", 1),
        owner,
        200);
    call(
        "POST",
        propertyPath + "/applications/" + application + "/approve",
        Map.of("version", 2, "message", "Approved for lease preparation."),
        owner,
        200);
    return new Inventory(owner, tenant, organization, property, unit, listing, application);
  }

  private Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    UUID id =
        id(
            node(
                call(
                    "POST",
                    "/api/v1/auth/register",
                    Map.of("email", email, "password", PASSWORD, "displayName", "Lease Account"),
                    null,
                    201)));
    Account account = new Account(id, email);
    login(account);
    return account;
  }

  private void login(Account account) throws Exception {
    account.token =
        node(call(
                "POST",
                "/api/v1/auth/login",
                Map.of("email", account.email, "password", PASSWORD),
                null,
                200))
            .get("accessToken")
            .asText();
  }

  private String organization(Account actor, String type) throws Exception {
    return node(call(
            "POST",
            "/api/v1/organizations",
            Map.of("name", "Lease business", "type", type),
            actor,
            201))
        .get("id")
        .asText();
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private JsonNode node(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private HttpRequest request(String method, String path, Object body, Account actor) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if (actor != null) request.header("Authorization", "Bearer " + actor.token);
    if (body != null) request.header("Content-Type", "application/json");
    return request
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  private HttpResponse<String> call(
      String method, String path, Object body, Account actor, int expected) throws Exception {
    var response =
        client.send(request(method, path, body, actor), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isEqualTo(expected);
    return response;
  }

  private static class Account {
    final UUID id;
    final String email;
    String token;

    Account(UUID id, String email) {
      this.id = id;
      this.email = email;
    }
  }

  private record Inventory(
      Account owner,
      Account tenant,
      String organization,
      String property,
      String unit,
      String listing,
      String application) {
    String propertyPath() {
      return "/api/v1/organizations/" + organization + "/properties/" + property;
    }

    String unitPath() {
      return propertyPath() + "/units/" + unit;
    }

    String base() {
      return propertyPath() + "/leases";
    }

    String path(JsonNode lease) {
      return base() + "/" + lease.get("id").asText();
    }
  }

  @TestConfiguration
  static class TestClockConfiguration {
    @Bean
    @Primary
    LeaseTestClock leaseClock() {
      return new LeaseTestClock();
    }
  }

  static class LeaseTestClock extends Clock {
    volatile Instant now = Instant.now();

    void days(long days) {
      now = now.plus(Duration.ofDays(days));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
