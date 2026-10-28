package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CaretakerOperationsTests {
  private static final String PASSWORD = "Property-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  private String base(Portfolio p) {
    return "/api/v1/organizations/" + p.organization + "/caretaker";
  }

  private JsonNode active(Portfolio p) throws Exception {
    var property = createProperty(p, propertyDetails("Operations property"));
    return node(
        success(
            "PATCH",
            p.base() + "/" + property.get("id").asText() + "/status",
            status(0, "ACTIVE"),
            p.account.token,
            200));
  }

  private Map<String, Object> decision(long v) {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "version",
        v,
        "message",
        "Reviewed the property and caretaker responsibilities.",
        "consent",
        true,
        "password",
        PASSWORD);
  }

  private Map<String, Object> action(long v, String message) {
    return Map.of("requestId", UUID.randomUUID(), "version", v, "message", message);
  }

  private Map<String, Object> offer(JsonNode p, Account member) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            p.get("id").asText(),
            "propertyVersion",
            p.get("version").asLong(),
            "userId",
            member.id,
            "capabilities",
            java.util.List.of("OPERATIONS", "INSPECTIONS", "MAINTENANCE", "NOTICES"),
            "responsibilities",
            "Daily property rounds and evidence-based operational follow-up.",
            "expiresAt",
            java.time.Instant.now().plusSeconds(86400).toString(),
            "consent",
            true,
            "password",
            PASSWORD));
  }

  private JsonNode assign(Portfolio o, JsonNode p, Account member) throws Exception {
    var g = node(success("POST", base(o) + "/assignments", offer(p, member), o.account.token, 200));
    return node(
        success(
            "POST",
            base(o) + "/assignments/" + g.get("id").asText() + "/accept",
            decision(0),
            member.token,
            200));
  }

  private Map<String, Object> task(JsonNode p, Account member) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "propertyId",
            p.get("id").asText(),
            "propertyVersion",
            p.get("version").asLong(),
            "assigneeId",
            member.id,
            "kind",
            "SAFETY",
            "priority",
            "HIGH",
            "title",
            "Inspect the entrance lighting",
            "description",
            "Check all entrance lights and document any failed fittings.",
            "locationLabel",
            "Common entrance",
            "dueAt",
            java.time.Instant.now().plusSeconds(3600).toString()));
  }

  @Test
  void invitationsRequireIndependentAcceptanceAndPropertyScope() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    var other = account();
    addMember(o, member, "MEMBER");
    addMember(o, other, "MEMBER");
    var p = active(o);
    var hidden = active(o);
    var offer = offer(p, member);
    var g = node(success("POST", base(o) + "/assignments", offer, o.account.token, 200));
    String path = base(o) + "/assignments/" + g.get("id").asText();
    assertThat(node(success("GET", base(o) + "/properties", null, member.token, 200)).size())
        .isZero();
    success("GET", o.base() + "/" + p.get("id").asText(), null, member.token, 404);
    success("GET", path, null, other.token, 404);
    success("POST", path + "/accept", decision(0), o.account.token, 403);
    var accept = decision(0);
    assertThat(
            node(success("POST", path + "/accept", accept, member.token, 200))
                .get("effective")
                .asBoolean())
        .isTrue();
    success("POST", path + "/accept", accept, member.token, 200);
    assertThat(node(success("GET", base(o) + "/properties", null, member.token, 200)).size())
        .isEqualTo(1);
    assertThat(node(success("GET", o.base(), null, member.token, 200)).size()).isEqualTo(1);
    success("GET", o.base() + "/" + hidden.get("id").asText(), null, member.token, 404);
    success(
        "POST",
        o.base() + "/" + p.get("id").asText() + "/units",
        unitDetails("Cannot edit"),
        member.token,
        403);
    success("POST", base(o) + "/assignments", offer, o.account.token, 200);
    offer.put("responsibilities", "Changed responsibilities must not replay against old terms.");
    success("POST", base(o) + "/assignments", offer, o.account.token, 409);
  }

  @Test
  void tasksFollowSubmissionManagerReviewAndAppendOnlyDiscussion() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    var other = account();
    addMember(o, member, "MEMBER");
    addMember(o, other, "MEMBER");
    var p = active(o);
    assign(o, p, member);
    assign(o, p, other);
    var body = task(p, member);
    var t = node(success("POST", base(o) + "/tasks", body, o.account.token, 200));
    String path = base(o) + "/tasks/" + t.get("id").asText();
    assertThat(success("GET", path, null, member.token, 200).headers().firstValue("Cache-Control"))
        .contains("no-store");
    success("GET", path, null, other.token, 404);
    success("POST", path + "/approve", action(0, "Cannot skip submission"), member.token, 403);
    success(
        "POST",
        path + "/start",
        action(0, "Starting entrance lighting inspection."),
        member.token,
        200);
    success(
        "POST",
        path + "/submit",
        action(1, "All fittings checked; two lights replaced and verified."),
        member.token,
        200);
    success(
        "POST", path + "/approve", action(2, "Self approval is unavailable."), member.token, 403);
    success(
        "POST",
        path + "/rework",
        action(2, "Please also check the emergency fitting."),
        o.account.token,
        200);
    success(
        "POST",
        path + "/submit",
        action(3, "Emergency fitting and all remaining lights verified."),
        member.token,
        200);
    var approve = action(4, "Completion reviewed against the task evidence.");
    success("POST", path + "/approve", approve, o.account.token, 200);
    success("POST", path + "/approve", approve, o.account.token, 200);
    success("POST", path + "/start", action(5, "Cannot reopen approved task."), member.token, 409);
    success(
        "POST",
        path + "/comment",
        action(5, "Thank you, inspection history retained."),
        member.token,
        200);
    assertThat(node(success("GET", path, null, o.account.token, 200)).get("version").asLong())
        .isEqualTo(5);
    assertThat(node(success("GET", path + "/history", null, member.token, 200)).size())
        .isEqualTo(7);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE property_operations_tasks SET title='Tamper' WHERE id=?",
                    UUID.fromString(t.get("id").asText())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void revokedMembershipAndPropertyArchiveCannotReviveAssignments() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    addMember(o, member, "MEMBER");
    var p = active(o);
    var g = assign(o, p, member);
    var t = node(success("POST", base(o) + "/tasks", task(p, member), member.token, 200));
    success(
        "DELETE",
        "/api/v1/organizations/" + o.organization + "/members/" + member.id,
        null,
        o.account.token,
        204);
    addMember(o, member, "MEMBER");
    success("GET", base(o) + "/tasks/" + t.get("id").asText(), null, member.token, 404);
    assertThat(
            node(success(
                    "GET",
                    base(o) + "/assignments/" + g.get("id").asText(),
                    null,
                    member.token,
                    200))
                .get("status")
                .asText())
        .isEqualTo("REVOKED");
    g = assign(o, p, member);
    success(
        "PATCH",
        o.base() + "/" + p.get("id").asText() + "/status",
        status(1, "ARCHIVED"),
        o.account.token,
        200);
    success(
        "PATCH",
        o.base() + "/" + p.get("id").asText() + "/status",
        status(2, "ACTIVE"),
        o.account.token,
        200);
    assertThat(node(success("GET", base(o) + "/properties", null, member.token, 200)).size())
        .isZero();
  }

  @Test
  void changedPropertyStaleDecisionsAndFailedReauthenticationAreRejected() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    addMember(o, member, "MEMBER");
    var p = active(o);
    var body = offer(p, member);
    var g = node(success("POST", base(o) + "/assignments", body, o.account.token, 200));
    String path = base(o) + "/assignments/" + g.get("id").asText();
    var wrong = new HashMap<>(decision(0));
    wrong.put("password", "Incorrect-password-42");
    success("POST", path + "/accept", wrong, member.token, 401);
    success(
        "PUT",
        o.base() + "/" + p.get("id").asText(),
        edit(1, propertyDetails("Changed offered property")),
        o.account.token,
        200);
    success("POST", path + "/accept", decision(0), member.token, 409);
    success("POST", path + "/decline", decision(1), member.token, 409);
    success("POST", path + "/decline", decision(0), member.token, 200);
  }

  @Test
  void assignmentExpiryIsCheckedAfterWaitingForThePropertyLock() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    addMember(o, member, "MEMBER");
    var p = active(o);
    var body = offer(p, member);
    var expiry = java.time.Instant.now().plusSeconds(5);
    body.put("expiresAt", expiry.toString());
    var g = node(success("POST", base(o) + "/assignments", body, o.account.token, 200));
    success(
        "POST",
        base(o) + "/assignments/" + g.get("id").asText() + "/accept",
        decision(0),
        member.token,
        200);
    try (var c = jdbc.getDataSource().getConnection()) {
      c.setAutoCommit(false);
      try (var st = c.prepareStatement("SELECT id FROM properties WHERE id=? FOR UPDATE")) {
        st.setObject(1, UUID.fromString(p.get("id").asText()));
        st.executeQuery().close();
        var pending = async("POST", base(o) + "/tasks", task(p, member), member.token);
        Thread.sleep(200);
        assertThat(pending.isDone()).isFalse();
        long wait =
            java.time.Duration.between(java.time.Instant.now(), expiry.plusMillis(200)).toMillis();
        if (wait > 0) Thread.sleep(wait);
        c.commit();
        assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(404);
      }
    }
  }

  @Test
  void taskAssignmentLocationAndReplayCannotCrossPropertyOrMemberBoundaries() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    var other = account();
    addMember(o, member, "MEMBER");
    addMember(o, other, "MEMBER");
    var p = active(o);
    var q = active(o);
    assign(o, p, member);
    var body = task(p, member);
    body.put("assigneeId", other.id);
    success("POST", base(o) + "/tasks", body, member.token, 403);
    body.put("assigneeId", member.id);
    body.put("propertyId", q.get("id").asText());
    success("POST", base(o) + "/tasks", body, member.token, 404);
    body.put("propertyId", p.get("id").asText());
    body.remove("locationLabel");
    success("POST", base(o) + "/tasks", body, member.token, 400);
    body.put("locationLabel", "Entrance");
    var t = node(success("POST", base(o) + "/tasks", body, member.token, 200));
    success("POST", base(o) + "/tasks", body, member.token, 200);
    body.put("description", "Different source instruction payload.");
    success("POST", base(o) + "/tasks", body, member.token, 409);
    success(
        "POST",
        base(o) + "/tasks/" + t.get("id").asText() + "/start",
        action(99, "A stale request cannot change this task."),
        member.token,
        409);
  }

  @Test
  void privatePhotoEvidenceIsNormalizedReplaySafeAndRevocationScoped() throws Exception {
    var o = portfolio("LANDLORD");
    var member = account();
    var teammate = account();
    addMember(o, member, "MEMBER");
    addMember(o, teammate, "MEMBER");
    var p = active(o);
    var g = assign(o, p, member);
    assign(o, p, teammate);
    var t = node(success("POST", base(o) + "/tasks", task(p, member), o.account.token, 200));
    String path = base(o) + "/tasks/" + t.get("id").asText();
    String boundary = "zaza-" + UUID.randomUUID();
    var out = new java.io.ByteArrayOutputStream();
    for (var e :
        Map.of(
                "requestId",
                UUID.randomUUID().toString(),
                "version",
                "0",
                "caption",
                "Entrance lighting verified")
            .entrySet())
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + e.getKey()
                  + "\"\r\n\r\n"
                  + e.getValue()
                  + "\r\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"evidence.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        out);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var upload =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/evidence"))
            .header("Authorization", "Bearer " + member.token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
    assertThat(client.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    assertThat(client.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    var evidence = node(success("GET", path + "/evidence", null, member.token, 200));
    assertThat(evidence.size()).isEqualTo(1);
    var id = evidence.get(0).get("id").asText();
    var content =
        client.send(
            request("GET", path + "/evidence/" + id + "/content", null, member.token),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(content.statusCode()).isEqualTo(200);
    assertThat(content.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content.body())))
        .isEqualTo(evidence.get(0).get("sha256").asText());
    success("GET", path + "/evidence/" + id + "/content", null, teammate.token, 404);
    success(
        "POST",
        base(o) + "/assignments/" + g.get("id").asText() + "/revoke",
        decision(1),
        o.account.token,
        200);
    success("GET", path + "/evidence/" + id + "/content", null, member.token, 404);
    success("GET", path + "/evidence/" + id + "/content", null, o.account.token, 200);
  }

  @Test
  void ownerCanRevokeAgencyCaretakersAndDelegationChangesKeepOnlyOwnerStaff() throws Exception {
    var o = portfolio("LANDLORD");
    var agency = portfolio("AGENCY");
    var ownerStaff = account();
    var agent = account();
    addMember(o, ownerStaff, "MEMBER");
    addMember(agency, agent, "MEMBER");
    var p = active(o);
    assign(o, p, ownerStaff);
    String property = o.base() + "/" + p.get("id").asText();
    p =
        node(
            success(
                "PATCH",
                property + "/management",
                management(1, agency.organization),
                o.account.token,
                200));
    var g = assign(agency, p, agent);
    assertThat(
            node(success(
                    "GET",
                    base(o) + "/assignments?propertyId=" + p.get("id").asText(),
                    null,
                    o.account.token,
                    200))
                .size())
        .isEqualTo(2);
    success(
        "POST",
        base(o) + "/assignments/" + g.get("id").asText() + "/revoke",
        decision(1),
        o.account.token,
        200);
    assertThat(node(success("GET", base(agency) + "/properties", null, agent.token, 200)).size())
        .isZero();
    g = assign(agency, p, agent);
    success("PATCH", property + "/management", management(2, null), o.account.token, 200);
    assertThat(node(success("GET", base(o) + "/properties", null, ownerStaff.token, 200)).size())
        .isEqualTo(1);
    success(
        "PATCH",
        property + "/management",
        management(3, agency.organization),
        o.account.token,
        200);
    assertThat(node(success("GET", base(agency) + "/properties", null, agent.token, 200)).size())
        .isZero();
  }

  private Portfolio portfolio(String type) throws Exception {
    Account actor = account();
    JsonNode organization =
        node(
            success(
                "POST",
                "/api/v1/organizations",
                Map.of("name", "Property test organization", "type", type),
                actor.token,
                201));
    return new Portfolio(actor, organization.get("id").asText());
  }

  private Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    JsonNode registered =
        node(
            success(
                "POST",
                "/api/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "displayName", "Property test"),
                null,
                201));
    JsonNode loggedIn =
        node(
            success(
                "POST",
                "/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD),
                null,
                200));
    return new Account(
        UUID.fromString(registered.get("id").asText()),
        email,
        loggedIn.get("accessToken").asText());
  }

  private void addMember(Portfolio portfolio, Account member, String role) throws Exception {
    success(
        "POST",
        "/api/v1/organizations/" + portfolio.organization + "/members",
        Map.of("email", member.email, "role", role),
        portfolio.account.token,
        201);
  }

  private JsonNode createProperty(Portfolio portfolio, Object details) throws Exception {
    return node(success("POST", portfolio.base(), details, portfolio.account.token, 201));
  }

  private Map<String, Object> propertyDetails(String name) {
    return new HashMap<>(
        Map.of(
            "name",
            name,
            "description",
            "A property management test",
            "type",
            "HOUSE",
            "countryCode",
            "ke",
            "city",
            "Nairobi",
            "address",
            "Test street 1"));
  }

  private Map<String, Object> unitDetails(String label) {
    return new HashMap<>(
        Map.of(
            "label",
            label,
            "bedrooms",
            2,
            "bathrooms",
            1,
            "floorAreaSqm",
            "72.50",
            "monthlyRent",
            "25000.50",
            "deposit",
            "50000.25",
            "currency",
            "kes",
            "availability",
            "AVAILABLE"));
  }

  private Map<String, Object> edit(long version, Object details) {
    return Map.of("version", version, "details", details);
  }

  private Map<String, Object> status(long version, String status) {
    return Map.of("version", version, "status", status);
  }

  private Map<String, Object> management(long version, String organization) {
    Map<String, Object> result = new HashMap<>();
    result.put("version", version);
    result.put("managerOrganizationId", organization);
    return result;
  }

  private JsonNode node(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private HttpResponse<String> success(
      String method, String path, Object body, String token, int status) throws Exception {
    var response = call(method, path, body, token);
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isEqualTo(status);
    return response;
  }

  private HttpRequest request(String method, String path, Object body, String token) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body != null) builder.header("Content-Type", "application/json");
    return builder
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  private HttpResponse<String> call(String method, String path, Object body, String token)
      throws Exception {
    return client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> async(
      String method, String path, Object body, String token) {
    return client.sendAsync(
        request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
  }

  private record Account(UUID id, String email, String token) {}

  private record Portfolio(Account account, String organization) {
    String base() {
      return "/api/v1/organizations/" + organization + "/properties";
    }
  }
}
