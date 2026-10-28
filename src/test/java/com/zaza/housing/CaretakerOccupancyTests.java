package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zaza.communication.messages-per-minute=4",
      "zaza.communication.enquiries-per-hour=2"
    })
@ActiveProfiles("test")
class CaretakerOccupancyTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired CaretakerClock clock;
  private static final java.nio.file.Path IMAGE_ROOT =
      java.nio.file.Path.of(".work", "work-evidence-tests-" + UUID.randomUUID()).toAbsolutePath();

  @org.springframework.test.context.DynamicPropertySource
  static void images(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("zaza.images.directory", () -> IMAGE_ROOT.toString());
  }

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-11-01T12:00:00Z");
  }

  private String care(Inventory i) {
    return "/api/v1/organizations/" + i.organization + "/caretaker";
  }

  private String operational(Inventory i) {
    return care(i) + "/properties/" + i.property;
  }

  private JsonNode assign(Inventory i, Account member, List<String> capabilities) throws Exception {
    call(
        "POST",
        "/api/v1/organizations/" + i.organization + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        i.owner,
        201);
    var p = node(call("GET", i.propertyPath(), null, i.owner, 200));
    var body = new HashMap<String, Object>();
    body.put("requestId", UUID.randomUUID());
    body.put("propertyId", i.property);
    body.put("propertyVersion", p.get("version").asLong());
    body.put("userId", member.id);
    body.put("capabilities", capabilities);
    body.put("responsibilities", "Daily property rounds, inspection preparation and notices.");
    body.put("expiresAt", Instant.now().plus(Duration.ofDays(30)).toString());
    body.put("consent", true);
    body.put("password", PASSWORD);
    var g = node(call("POST", care(i) + "/assignments", body, i.owner, 200));
    return node(
        call(
            "POST",
            care(i) + "/assignments/" + g.get("id").asText() + "/accept",
            decision(0),
            member,
            200));
  }

  private Map<String, Object> decision(long version) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "version",
        version,
        "message",
        "Reviewed the exact property responsibilities and decision.",
        "consent",
        true,
        "password",
        PASSWORD);
  }

  private Map<String, Object> command(long version) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "version",
        version,
        "message",
        "I reviewed this exact operational notice and appointment.",
        "consent",
        true);
  }

  private Map<String, Object> notice(InspectionFixture f) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "leaseId",
            f.lease.get("id").asText(),
            "kind",
            "INSPECTION",
            "title",
            "Proposed condition inspection",
            "message",
            "Please review the proposed appointment and reply with your availability.",
            "startsAt",
            clock.instant().plus(Duration.ofDays(1)).toString(),
            "endsAt",
            clock.instant().plus(Duration.ofDays(1)).plusSeconds(3600).toString()));
  }

  @Test
  void caretakerGetsOperationalOccupancyAndInspectionRightsWithoutLeaseOrBillingAuthority()
      throws Exception {
    var f = signedTracked();
    var worker = account();
    assign(f.i, worker, List.of("OPERATIONS", "INSPECTIONS"));
    var response =
        call("GET", operational(f.i) + "/leases/" + f.lease.get("id").asText(), null, worker, 200);
    var occupancy = node(response);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(occupancy.has("monthlyRent")).isFalse();
    assertThat(occupancy.has("revision")).isFalse();
    assertThat(occupancy.get("inspectionTrackingEnabled").asBoolean()).isTrue();
    call("GET", f.i.path(f.lease), null, worker, 403);
    call("GET", f.i.path(f.lease) + "/checkout", null, worker, 403);
    call("POST", f.i.path(f.lease) + "/inspections/enable", enableBody(f.lease), worker, 403);
    var r =
        node(
            call(
                "POST",
                f.i.path(f.lease) + "/inspections",
                Map.of("requestId", UUID.randomUUID(), "kind", "MOVE_IN", "details", details()),
                worker,
                201));
    call("POST", m(f, r) + "/share", review(r), worker, 200);
    r = node(call("GET", m(f, r), null, worker, 200));
    call("POST", t(f, r) + "/accept", review(r), worker, 404);
    accept(f, r);
    call(
        "POST",
        f.i.path(manager(f.i, f.lease)) + "/activate",
        Map.of(
            "version", manager(f.i, f.lease).get("version").asLong(), "possessionConfirmed", true),
        worker,
        403);
  }

  @Test
  void tenantReceivesOnlyPublishedImmutableNoticesAndControlsTheirReplies() throws Exception {
    var f = signedTracked();
    var worker = account();
    assign(f.i, worker, List.of("OPERATIONS", "NOTICES"));
    var body = notice(f);
    var n = node(call("POST", operational(f.i) + "/notices", body, worker, 200));
    String manager = operational(f.i) + "/notices/" + n.get("id").asText();
    String tenant = "/api/v1/me/property-notices/" + n.get("id").asText();
    call("GET", tenant, null, f.i.tenant, 404);
    call("POST", manager + "/publish", command(0), worker, 403);
    call("POST", manager + "/publish", command(0), f.i.owner, 200);
    call("GET", tenant, null, f.i.tenant, 200);
    call("GET", tenant, null, account(), 404);
    var reply = command(1);
    call("POST", tenant + "/accept", reply, f.i.tenant, 200);
    call("POST", tenant + "/accept", reply, f.i.tenant, 200);
    assertThat(node(call("GET", tenant, null, f.i.tenant, 200)).get("tenantResponse").asText())
        .isEqualTo("ACCEPT");
    call("POST", tenant + "/reschedule", command(1), f.i.tenant, 409);
    call("POST", tenant + "/reschedule", command(2), f.i.tenant, 200);
    call("POST", tenant + "/cancel", command(3), f.i.tenant, 403);
    call("POST", manager + "/cancel", command(3), f.i.owner, 200);
    assertThat(node(call("GET", tenant, null, f.i.tenant, 200)).get("status").asText())
        .isEqualTo("CANCELLED");
    assertThat(node(call("GET", tenant + "/history", null, f.i.tenant, 200)).size()).isEqualTo(5);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE property_operational_notices SET message='Changed' WHERE id=?", id(n)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void missingOrRevokedCapabilitiesBlockPreparationAndUnpublishedDrafts() throws Exception {
    var f = signedTracked();
    var worker = account();
    var g = assign(f.i, worker, List.of("OPERATIONS", "NOTICES"));
    call("GET", f.i.path(f.lease) + "/inspections", null, worker, 404);
    var n = node(call("POST", operational(f.i) + "/notices", notice(f), worker, 200));
    call(
        "POST",
        care(f.i) + "/assignments/" + g.get("id").asText() + "/revoke",
        decision(1),
        f.i.owner,
        200);
    call(
        "POST",
        operational(f.i) + "/notices/" + n.get("id").asText() + "/publish",
        command(0),
        f.i.owner,
        409);
    call("GET", operational(f.i) + "/occupancy", null, worker, 404);
    call("GET", "/api/v1/me/property-notices/" + n.get("id").asText(), null, f.i.tenant, 404);
  }

  @Test
  void invalidAppointmentsAndUnassignedReadersCannotReachResidents() throws Exception {
    var f = signedTracked();
    var worker = account();
    assign(f.i, worker, List.of("OPERATIONS"));
    call("POST", operational(f.i) + "/notices", notice(f), worker, 404);
    var body = notice(f);
    body.put("endsAt", clock.instant().plus(Duration.ofDays(1)).plusSeconds(60).toString());
    call("POST", operational(f.i) + "/notices", body, f.i.owner, 400);
    call("GET", operational(f.i) + "/occupancy", null, account(), 404);
  }

  private record InspectionFixture(Inventory i, JsonNode lease) {}

  private InspectionFixture signedTracked() throws Exception {
    var i = inventory();
    var lease = executed(i);
    call("POST", i.path(lease) + "/inspections/enable", enableBody(lease), i.owner, 200);
    return new InspectionFixture(i, lease);
  }

  private InspectionFixture activeTracked() throws Exception {
    var f = signedTracked();
    accept(f, share(f, create(f, "MOVE_IN")));
    var lease = activate(f);
    return new InspectionFixture(f.i, lease);
  }

  private InspectionFixture lateTracked() throws Exception {
    var f = activeTracked();
    clock.days(30);
    refresh(f.i);
    return f;
  }

  private void refresh(Inventory i) throws Exception {
    login(i.owner);
    login(i.tenant);
  }

  private HashMap<String, Object> enableBody(JsonNode lease) {
    return new HashMap<>(
        Map.of("requestId", UUID.randomUUID(), "leaseVersion", lease.get("version").asLong()));
  }

  private JsonNode activate(InspectionFixture f) throws Exception {
    var lease = manager(f.i, f.lease);
    return node(
        call(
            "POST",
            f.i.path(lease) + "/activate",
            Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
            f.i.owner,
            200));
  }

  private void release(Inventory i, JsonNode lease, int expected) throws Exception {
    call(
        "POST",
        i.path(lease) + "/release-unit",
        Map.of(
            "version",
            lease.get("version").asLong(),
            "vacancyConfirmed",
            true,
            "message",
            "Accepted handover and cleared checkout."),
        i.owner,
        expected);
  }

  private JsonNode create(InspectionFixture f, String kind) throws Exception {
    return createWith(f, kind, details(), 201);
  }

  private JsonNode createWith(InspectionFixture f, String kind, Object details, int expected)
      throws Exception {
    return node(
        call(
            "POST",
            f.i.path(f.lease) + "/inspections",
            Map.of("requestId", UUID.randomUUID(), "kind", kind, "details", details),
            f.i.owner,
            expected));
  }

  private String m(InspectionFixture f, JsonNode r) {
    return f.i.path(f.lease) + "/inspections/" + r.get("id").asText();
  }

  private String t(InspectionFixture f, JsonNode r) {
    return own(f.lease) + "/inspections/" + r.get("id").asText();
  }

  private HashMap<String, Object> review(JsonNode r) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            r.get("version").asLong(),
            "revisionId",
            r.get("revision").get("id").asText(),
            "documentHash",
            r.get("revision").get("documentHash").asText(),
            "consent",
            true,
            "vacancyConfirmed",
            r.get("kind").asText().equals("MOVE_OUT"),
            "password",
            PASSWORD,
            "reason",
            "I reviewed the exact report and handover."));
  }

  private JsonNode share(InspectionFixture f, JsonNode r) throws Exception {
    return node(call("POST", m(f, r) + "/share", review(r), f.i.owner, 200));
  }

  private JsonNode accept(InspectionFixture f, JsonNode r) throws Exception {
    return node(call("POST", t(f, r) + "/accept", review(r), f.i.tenant, 200));
  }

  private Map<String, Object> disputeBody(JsonNode r) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "version",
        r.get("version").asLong(),
        "revisionId",
        r.get("revision").get("id").asText(),
        "reason",
        "Please correct the wall condition and attach evidence.",
        "itemKeys",
        List.of("wall"));
  }

  private JsonNode dispute(InspectionFixture f, JsonNode r) throws Exception {
    return node(call("POST", t(f, r) + "/dispute", disputeBody(r), f.i.tenant, 200));
  }

  private JsonNode revise(InspectionFixture f, JsonNode r, Object details) throws Exception {
    return node(
        call(
            "PUT",
            m(f, r),
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                r.get("version").asLong(),
                "details",
                details),
            f.i.owner,
            200));
  }

  private Map<String, Object> item(String key, String condition) {
    return Map.of(
        "key",
        key,
        "area",
        "Living room",
        "name",
        key,
        "condition",
        condition,
        "quantity",
        1,
        "notes",
        "Documented condition",
        "estimatedCharge",
        "100.00");
  }

  private HashMap<String, Object> details() {
    return new HashMap<>(
        Map.of(
            "title",
            "Condition and handover report",
            "observedAt",
            clock.instant().toString(),
            "summary",
            "Jointly observed condition, readings and key inventory.",
            "items",
            List.of(item("wall", "GOOD")),
            "meters",
            List.of(
                Map.of(
                    "key",
                    "water",
                    "label",
                    "Water",
                    "serialNumber",
                    "Meter serial 001",
                    "reading",
                    "123.456",
                    "unit",
                    "m3")),
            "keys",
            List.of(
                Map.of(
                    "key",
                    "front",
                    "label",
                    "Front door keys",
                    "expectedCount",
                    2,
                    "transferredCount",
                    2)),
            "evidenceIds",
            List.of()));
  }

  private long count(String table, String column, String id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + "=?",
        Long.class,
        UUID.fromString(id));
  }

  private byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
    try (var out = new java.io.ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(image, "png", out);
      image.flush();
      return out.toByteArray();
    }
  }

  private long fileCount() throws Exception {
    try (var files = java.nio.file.Files.list(IMAGE_ROOT)) {
      return files.count();
    }
  }

  private HttpResponse<String> upload(
      InspectionFixture f, JsonNode r, Account actor, UUID requestId, byte[] image)
      throws Exception {
    String boundary = "inspection" + UUID.randomUUID().toString().replace("-", "");
    var out = new java.io.ByteArrayOutputStream();
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\";"
                + " filename=\"../../private.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(image);
    for (var e :
        Map.of(
                "requestId",
                requestId.toString(),
                "version",
                r.get("version").asText(),
                "caption",
                "Private inspection photograph")
            .entrySet())
      out.write(
          ("\r\n--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + e.getKey()
                  + "\"\r\n\r\n"
                  + e.getValue())
              .getBytes(StandardCharsets.UTF_8));
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var req =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + (actor == f.i.tenant ? t(f, r) : m(f, r))
                        + "/evidence"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + actor.token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
    return client.send(req, HttpResponse.BodyHandlers.ofString());
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
    String organization = organization(owner, "LANDLORD");
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
  static class CaretakerClockConfiguration {
    @Bean
    @Primary
    CaretakerClock leaseClock() {
      return new CaretakerClock();
    }
  }

  static class CaretakerClock extends Clock {
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
