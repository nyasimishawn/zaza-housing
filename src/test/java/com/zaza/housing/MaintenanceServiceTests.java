package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
class MaintenanceServiceTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkTestClock clock;
  private static final java.nio.file.Path IMAGE_ROOT =
      java.nio.file.Path.of(".work", "work-evidence-tests-" + UUID.randomUUID()).toAbsolutePath();

  @org.springframework.test.context.DynamicPropertySource
  static void images(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("zaza.images.directory", () -> IMAGE_ROOT.toString());
  }

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-10-15T12:00:00Z");
  }

  @Test
  void serviceProfileNeedsTheCorrectOrganizationAndExplicitPublication() throws Exception {
    Account a = account();
    String landlord = organization(a, "LANDLORD");
    call(
        "POST",
        "/api/v1/organizations/" + landlord + "/service-profile",
        profile("Nairobi"),
        a,
        400);
    String org = organization(a, "SERVICE_PROVIDER"),
        base = "/api/v1/organizations/" + org + "/service-profile";
    call("POST", base, profile("Nairobi"), a, 200);
    call("GET", "/api/v1/service-providers/" + org, null, null, 404);
    call("POST", base + "/state", Map.of("version", 0, "status", "ACTIVE"), a, 409);
    var d = new HashMap<>(offerDetails("PLUMBING"));
    d.put("priceUnit", "FIXED");
    call("POST", base + "/offers", d, a, 400);
    d.put("amount", "100.001");
    call("POST", base + "/offers", d, a, 400);
    d.put("amount", "100");
    d.put("currency", "JPY");
    call("POST", base + "/offers", d, a, 400);
  }

  @Test
  void directoryOnlyShowsActiveProfilesAndMatchingServiceAreas() throws Exception {
    String city = "ServiceCity-" + UUID.randomUUID();
    Provider p = provider(city, "PLUMBING");
    JsonNode profile = node(call("GET", p.directory(), null, null, 200));
    assertThat(profile.get("verificationStatus").asText()).isEqualTo("NOT_VERIFIED");
    assertThat(profile.toString()).doesNotContain("tenantId", "phone", "email", "storageKey");
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/service-providers?category=PLUMBING&country=KE&city="
                        + city
                        + "&limit=100",
                    null,
                    null,
                    200))
                .toString())
        .contains(p.org);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/service-providers?country=UG&city=Kampala&limit=100",
                    null,
                    null,
                    200))
                .toString())
        .doesNotContain(p.org);
    call("POST", p.profile() + "/state", Map.of("version", 1, "status", "PAUSED"), p.actor, 200);
    call("GET", p.directory(), null, null, 404);
    assertThat(node(call("GET", "/api/v1/service-providers?limit=100", null, null, 200)).toString())
        .doesNotContain(p.org);
  }

  @Test
  void providerProfilesAndOffersRejectStaleVersionsAndForeignIds() throws Exception {
    Provider p = provider(), other = provider();
    call("PUT", p.profile(), Map.of("version", 0, "details", profile("Nairobi")), p.actor, 409);
    call(
        "PUT",
        p.profile() + "/offers/" + p.offer,
        Map.of("version", 0, "details", offerDetails("PLUMBING")),
        p.actor,
        409);
    call(
        "PUT",
        p.profile() + "/offers/" + other.offer,
        Map.of("version", 1, "details", offerDetails("PLUMBING")),
        p.actor,
        404);
    Account member = account();
    call(
        "POST",
        "/api/v1/organizations/" + p.org + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        p.actor,
        201);
    call("GET", p.profile(), null, member, 403);
    call("GET", p.profile() + "/offers", null, other.actor, 404);
  }

  @Test
  void tenantMaintenanceRequiresTheirOwnActiveLease() throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    var body = createBody(lease, "MAINTENANCE", null);
    call("POST", "/api/v1/me/work-requests", body, i.tenant, 409);
    Account other = account();
    call("POST", "/api/v1/me/work-requests", body, other, 404);
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/activate",
                Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
                i.owner,
                200));
    body = createBody(lease, "MAINTENANCE", null);
    call("POST", "/api/v1/me/work-requests", body, i.tenant, 200);
  }

  @Test
  void creationIsIdempotentAndRejectsAReusedKeyWithDifferentContent() throws Exception {
    Inventory i = inventory();
    JsonNode lease = active(i);
    var body = createBody(lease, "MAINTENANCE", null);
    JsonNode first = node(call("POST", "/api/v1/me/work-requests", body, i.tenant, 200));
    JsonNode again = node(call("POST", "/api/v1/me/work-requests", body, i.tenant, 200));
    assertThat(first.get("id").asText()).isEqualTo(again.get("id").asText());
    body.put("title", "Changed payload");
    call("POST", "/api/v1/me/work-requests", body, i.tenant, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM work_requests WHERE lease_id=?", Integer.class, id(lease)))
        .isEqualTo(1);
  }

  @Test
  void maintenanceCanRequestInformationBeforeTriageAndAssignment() throws Exception {
    var c = maintenance();
    var b = cmd(c);
    b.put("priority", "URGENT");
    call("POST", c.manager() + "/request-information", b, c.i.owner, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("NEEDS_INFORMATION");
    call("POST", c.tenant() + "/respond", cmd(c), c.i.tenant, 200);
    triage(c);
    assertThat(view(c).get("status").asText()).isEqualTo("TRIAGED");
    call("POST", c.tenant() + "/cancel", cmd(c), c.i.tenant, 409);
  }

  @Test
  void managersCreateMaintenanceButCannotCreateOrReadPrivateTenantBookings() throws Exception {
    Inventory i = inventory();
    JsonNode lease = active(i);
    Provider p = provider();
    call(
        "POST",
        i.propertyPath() + "/work-requests",
        createBody(lease, "MAINTENANCE", null),
        i.owner,
        200);
    call(
        "POST",
        i.propertyPath() + "/work-requests",
        createBody(lease, "SERVICE_BOOKING", p),
        i.owner,
        403);
    JsonNode w =
        node(
            call(
                "POST",
                "/api/v1/me/work-requests",
                createBody(lease, "SERVICE_BOOKING", p),
                i.tenant,
                200));
    call("GET", i.propertyPath() + "/work-requests/" + w.get("id").asText(), null, i.owner, 404);
    JsonNode inbox = node(call("GET", "/api/v1/me/notifications", null, i.owner, 200));
    assertThat(inbox.toString()).doesNotContain(w.get("id").asText());
    JsonNode providerInbox =
        node(call("GET", "/api/v1/me/notifications?category=SERVICES", null, p.actor, 200));
    assertThat(providerInbox.toString()).contains(w.get("id").asText());
    for (var item : providerInbox) assertThat(item.get("category").asText()).isEqualTo("SERVICES");
  }

  @Test
  void guestsOutsidersAndUnassignedPropertyMembersCannotReadWork() throws Exception {
    var c = maintenance();
    Account other = account(), member = account();
    call(
        "POST",
        "/api/v1/organizations/" + c.i.organization + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        c.i.owner,
        201);
    call("GET", c.tenant(), null, null, 401);
    call("GET", c.tenant(), null, other, 404);
    call("GET", c.manager(), null, other, 404);
    call("GET", c.manager(), null, member, 404);
    call("GET", c.manager() + "/history", null, other, 404);
    assertThat(node(call("GET", "/api/v1/me/work-requests", null, other, 200)).size()).isZero();
  }

  @Test
  void dispatchRequiresCurrentActiveCategoryAndCoverage() throws Exception {
    var c = maintenance();
    triage(c);
    Provider outside = provider("Kampala", "PLUMBING");
    var b = cmd(c);
    b.put("offerId", outside.offer);
    b.put("offerVersion", 1);
    call("POST", c.manager() + "/assign", b, c.i.owner, 409);
    Provider wrong = provider("Nairobi", "CLEANING");
    b.put("offerId", wrong.offer);
    call("POST", c.manager() + "/assign", b, c.i.owner, 409);
    Provider p = provider();
    b.put("offerId", p.offer);
    b.put("offerVersion", 0);
    call("POST", c.manager() + "/assign", b, c.i.owner, 409);
    call(
        "POST",
        p.profile() + "/offers/" + p.offer + "/state",
        Map.of("version", 1, "status", "ARCHIVED"),
        p.actor,
        200);
    b.put("offerVersion", 2);
    call("POST", c.manager() + "/assign", b, c.i.owner, 409);
  }

  @Test
  void providerJobViewsRevealPremisesOnlyAfterAcceptance() throws Exception {
    var c = assigned();
    JsonNode before = node(call("GET", c.job(), null, c.p.actor, 200));
    assertThat(before.get("address").isNull()).isTrue();
    assertThat(before.toString())
        .doesNotContain(
            "tenantId", "tenantEmail", "leaseId", "propertyId", "ownerOrganizationId", "unitId");
    call("POST", c.job() + "/accept", cmd(c), c.p.actor, 200);
    JsonNode after = node(call("GET", c.job(), null, c.p.actor, 200));
    assertThat(after.get("address").asText()).isEqualTo("Private lease premises");
    assertThat(
            call("GET", c.job(), null, c.p.actor, 200)
                .headers()
                .firstValue("Cache-Control")
                .orElse(""))
        .contains("no-store");
  }

  @Test
  void providerDeclineReturnsOnlyAcknowledgementAndRevokesAccess() throws Exception {
    var c = assigned();
    var result = node(call("POST", c.job() + "/decline", cmd(c), c.p.actor, 200));
    assertThat(result.toString()).doesNotContain("address", "description", "leaseId", "unitLabel");
    call("GET", c.job(), null, c.p.actor, 404);
    assertThat(view(c).get("status").asText()).isEqualTo("TRIAGED");
    Provider replacement = provider();
    assign(c, replacement);
    call("GET", c.job(), null, replacement.actor, 200);
  }

  @Test
  void providerMembersAndRevokedAdministratorsLoseJobAccess() throws Exception {
    var c = assigned();
    Account admin = account();
    call(
        "POST",
        "/api/v1/organizations/" + c.p.org + "/members",
        Map.of("email", admin.email, "role", "ADMIN"),
        c.p.actor,
        201);
    call("GET", c.job(), null, admin, 200);
    call(
        "DELETE",
        "/api/v1/organizations/" + c.p.org + "/members/" + admin.id,
        null,
        c.p.actor,
        204);
    call("GET", c.job(), null, admin, 404);
    Account member = account();
    call(
        "POST",
        "/api/v1/organizations/" + c.p.org + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        c.p.actor,
        201);
    call("GET", c.job(), null, member, 403);
  }

  @Test
  void delegatedAgencyMayTriageButOnlyOwnerApprovesMaintenanceQuote() throws Exception {
    var c = accepted();
    String q = quote(c);
    Account agency = account();
    String org = organization(agency, "AGENCY");
    call(
        "PATCH",
        c.i.propertyPath() + "/management",
        Map.of("version", 1, "managerOrganizationId", org),
        c.i.owner,
        200);
    String path =
        "/api/v1/organizations/" + org + "/properties/" + c.i.property + "/work-requests/" + c.id;
    call("GET", path, null, agency, 200);
    call("POST", path + "/quotes/" + q + "/accept", consent(c), agency, 403);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    var revoke = new HashMap<String, Object>();
    revoke.put("version", 2);
    revoke.put("managerOrganizationId", null);
    call("PATCH", c.i.propertyPath() + "/management", revoke, c.i.owner, 200);
    call("GET", path, null, agency, 404);
  }

  @Test
  void maintenanceQuotesStayPrivateFromOccupantButBookingQuotesDoNot() throws Exception {
    var c = accepted();
    quote(c);
    call("GET", c.tenant() + "/quotes", null, c.i.tenant, 403);
    call("GET", c.manager() + "/quotes", null, c.i.owner, 200);
    var booking = booking(c.p);
    call("POST", booking.job() + "/accept", cmd(booking), c.p.actor, 200);
    quote(booking);
    assertThat(
            node(call("GET", booking.tenant() + "/quotes", null, booking.i.tenant, 200))
                .get(0)
                .get("amount")
                .decimalValue())
        .isEqualByComparingTo("1500");
  }

  @Test
  void quoteApprovalBindsTheCurrentQuoteAndRejectsExpiredEvidence() throws Exception {
    var c = accepted();
    String old = quote(c), current = quote(c);
    call("POST", c.manager() + "/quotes/" + old + "/accept", consent(c), c.i.owner, 409);
    clock.days(3);
    loginAll(c);
    call("POST", c.manager() + "/quotes/" + current + "/accept", consent(c), c.i.owner, 409);
    assertThat(
            node(call("GET", c.manager() + "/quotes", null, c.i.owner, 200))
                .get(0)
                .get("status")
                .asText())
        .isEqualTo("EXPIRED");
  }

  @Test
  void selectedOfferSnapshotSurvivesLaterPriceAndCategoryEdits() throws Exception {
    var c = accepted();
    var d = new HashMap<>(offerDetails("CLEANING"));
    d.put("currency", "USD");
    call(
        "PUT",
        c.p.profile() + "/offers/" + c.p.offer,
        Map.of("version", 1, "details", d),
        c.p.actor,
        200);
    String q = quote(c);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT snapshot->'details'->>'currency' FROM work_assignments WHERE id=?",
                String.class,
                UUID.fromString(view(c).get("assignmentId").asText())))
        .isEqualTo("KES");
  }

  @Test
  void schedulingNeedsAuthorizedQuoteAndSeparateOccupantAccessConsent() throws Exception {
    var c = accepted();
    var b = cmd(c);
    b.remove("message");
    b.put("startAt", clock.instant().plusSeconds(3600).toString());
    b.put("endAt", clock.instant().plusSeconds(7200).toString());
    b.put("accessInstructions", "Occupant will meet technician.");
    call("POST", c.job() + "/visits", b, c.p.actor, 409);
    String q = quote(c);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    var consent = consent(c);
    consent.put("consent", false);
    call(
        "POST",
        c.tenant() + "/visits/" + view(c).get("visitId").asText() + "/accept",
        consent,
        c.i.tenant,
        400);
    call(
        "POST",
        c.tenant() + "/visits/" + view(c).get("visitId").asText() + "/accept",
        consent(c),
        c.i.owner,
        404);
    acceptVisit(c);
    assertThat(view(c).get("status").asText()).isEqualTo("SCHEDULED");
  }

  @Test
  void internalMaintenanceStillRequiresAVisitAndOccupantConfirmation() throws Exception {
    var c = maintenance();
    triage(c);
    call("POST", c.manager() + "/assign-internal", cmd(c), c.i.owner, 200);
    call("POST", c.manager() + "/start", cmd(c), c.i.owner, 409);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    clock.now = clock.instant().plusSeconds(3600);
    loginAll(c);
    call("POST", c.manager() + "/start", cmd(c), c.i.owner, 200);
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("COMPLETED");
  }

  @Test
  void fullProviderMaintenanceNeedsOccupantAndManagerCompletionReviews() throws Exception {
    var c = started();
    submit(c);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 409);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.p.actor, 404);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("AWAITING_APPROVAL");
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM billing_accounts WHERE lease_id=?",
                Integer.class,
                id(c.lease)))
        .isZero();
    assertThat(manager(c.i, c.lease).get("version").asLong())
        .isEqualTo(c.lease.get("version").asLong());
  }

  @Test
  void personalServiceBookingCompletesOnCustomerConfirmation() throws Exception {
    Provider p = provider();
    var c = booking(p);
    call("POST", c.job() + "/accept", cmd(c), p.actor, 200);
    String q = quote(c);
    call("POST", c.tenant() + "/quotes/" + q + "/accept", consent(c), c.i.tenant, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    clock.now = clock.instant().plusSeconds(3600);
    loginAll(c);
    call("POST", c.job() + "/start", cmd(c), p.actor, 200);
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("COMPLETED");
    call("GET", c.manager(), null, c.i.owner, 404);
  }

  @Test
  void acceptedVisitsPreventDoubleBookingAcrossDifferentCustomers() throws Exception {
    Provider p = provider();
    var first = booking(p);
    var second = booking(p);
    for (var c : List.of(first, second)) {
      call("POST", c.job() + "/accept", cmd(c), p.actor, 200);
      String q = quote(c);
      call("POST", c.tenant() + "/quotes/" + q + "/accept", consent(c), c.i.tenant, 200);
      propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    }
    var firstBody = consent(first);
    var secondBody = consent(second);
    var a =
        client.sendAsync(
            request(
                "POST",
                first.tenant() + "/visits/" + view(first).get("visitId").asText() + "/accept",
                firstBody,
                first.i.tenant),
            HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request(
                "POST",
                second.tenant() + "/visits/" + view(second).get("visitId").asText() + "/accept",
                secondBody,
                second.i.tenant),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
  }

  @Test
  void adjacentVisitsAreAllowedAndCalendarIsPrivate() throws Exception {
    var first = scheduled();
    var second = booking(first.p);
    call("POST", second.job() + "/accept", cmd(second), second.p.actor, 200);
    String q = quote(second);
    call(
        "POST",
        second.tenant() + "/quotes/" + q + "/accept",
        consent(second),
        second.i.tenant,
        200);
    propose(second, clock.instant().plusSeconds(7200), clock.instant().plusSeconds(10800));
    acceptVisit(second);
    String calendar =
        "/api/v1/organizations/"
            + first.p.org
            + "/service-jobs/calendar?from="
            + clock.instant()
            + "&to="
            + clock.instant().plus(Duration.ofDays(1));
    assertThat(node(call("GET", calendar, null, first.p.actor, 200)).size()).isEqualTo(2);
    call("GET", calendar, null, first.i.owner, 404);
    call(
        "GET",
        "/api/v1/organizations/"
            + first.p.org
            + "/service-jobs/calendar?from="
            + clock.instant()
            + "&to="
            + clock.instant().plus(Duration.ofDays(32)),
        null,
        first.p.actor,
        400);
  }

  @Test
  void visitValidationAndStartWindowRequireReschedulingAMissedVisit() throws Exception {
    var c = scheduled();
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 409);
    clock.now = clock.instant().plusSeconds(7200);
    loginAll(c);
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 409);
    call("POST", c.tenant() + "/reschedule", cmd(c), c.i.tenant, 200);
    assertThat(view(c).get("visitId").isNull()).isTrue();
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 409);
    acceptVisit(c);
    assertThat(rows(c, "visits", c.i.tenant, c.tenant()).size()).isEqualTo(2);
  }

  @Test
  void providerCannotReleaseOrCancelWorkAfterStarting() throws Exception {
    var c = started();
    call("POST", c.job() + "/release", cmd(c), c.p.actor, 409);
    call("POST", c.manager() + "/cancel", cmd(c), c.i.owner, 409);
    call("POST", c.job() + "/disputes", cmd(c), c.p.actor, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("DISPUTED");
  }

  @Test
  void reworkPreservesTheDisputeAndRequiresFreshVisitConsent() throws Exception {
    var c = started();
    submit(c);
    call("POST", c.tenant() + "/disputes", cmd(c), c.i.tenant, 200);
    String dispute = view(c).get("disputeId").asText();
    call("POST", c.manager() + "/rework", cmd(c), c.i.owner, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(view(c).get("visitId").isNull()).isTrue();
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 409);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution FROM work_dispute_resolutions WHERE dispute_id=?",
                String.class,
                UUID.fromString(dispute)))
        .isEqualTo("REWORK");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE work_disputes SET reason='changed' WHERE id=?",
                    UUID.fromString(dispute)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void onlyRaisingPartyCanWithdrawABlockerAndPriorStateIsRestored() throws Exception {
    var c = started();
    call("POST", c.job() + "/disputes", cmd(c), c.p.actor, 200);
    call("POST", c.tenant() + "/withdraw-dispute", cmd(c), c.i.tenant, 403);
    call("POST", c.job() + "/withdraw-dispute", cmd(c), c.p.actor, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(view(c).get("visitId").isNull()).isFalse();
  }

  @Test
  void completedWorkCanOnlyBeReopenedByCustomerWithinFourteenDays() throws Exception {
    var c = completed();
    call("POST", c.job() + "/disputes", cmd(c), c.p.actor, 409);
    call("POST", c.manager() + "/disputes", cmd(c), c.i.owner, 409);
    clock.days(15);
    loginAll(c);
    call("POST", c.tenant() + "/disputes", cmd(c), c.i.tenant, 409);
  }

  @Test
  void marketplaceReviewsRequireCompletionAndCannotBeDuplicated() throws Exception {
    var c = started();
    var b = cmd(c);
    b.remove("message");
    b.put("rating", 5);
    b.put("comment", "The repairs were completed carefully.");
    b.put("publishReview", true);
    call("POST", c.tenant() + "/reviews", b, c.i.tenant, 409);
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    b = cmd(c);
    b.remove("message");
    b.put("rating", 5);
    b.put("comment", "The repairs were completed carefully.");
    b.put("publishReview", true);
    call("POST", c.tenant() + "/reviews", b, c.i.tenant, 200);
    call("POST", c.tenant() + "/reviews", b, c.i.tenant, 200);
    assertThat(node(call("GET", c.p.directory() + "/reviews", null, null, 200)).size())
        .isEqualTo(1);
    JsonNode p = node(call("GET", c.p.directory(), null, null, 200));
    assertThat(p.get("reviewCount").asInt()).isEqualTo(1);
    b.put("requestId", UUID.randomUUID());
    b.put("version", view(c).get("version").asLong());
    call("POST", c.tenant() + "/reviews", b, c.i.tenant, 409);
    call("POST", c.tenant() + "/disputes", cmd(c), c.i.tenant, 200);
    assertThat(node(call("GET", c.p.directory(), null, null, 200)).get("reviewCount").asInt())
        .isZero();
  }

  @Test
  void providerMembersCannotRateTheirOwnOrganization() throws Exception {
    var c = completed();
    call(
        "POST",
        "/api/v1/organizations/" + c.p.org + "/members",
        Map.of("email", c.i.tenant.email, "role", "MEMBER"),
        c.p.actor,
        201);
    var b = cmd(c);
    b.remove("message");
    b.put("rating", 5);
    b.put("comment", "A review of this completed job.");
    b.put("publishReview", true);
    call("POST", c.tenant() + "/reviews", b, c.i.tenant, 403);
  }

  @Test
  void internalNotesNeverReachOccupantsProvidersOrTheirInboxes() throws Exception {
    var c = accepted();
    var b = cmd(c);
    b.put("visibility", "INTERNAL");
    b.put("message", "PRIVATE_PROCUREMENT_NOTE");
    call("POST", c.manager() + "/notes", b, c.i.owner, 200);
    assertThat(rows(c, "history", c.i.owner, c.manager()).toString())
        .contains("PRIVATE_PROCUREMENT_NOTE");
    assertThat(rows(c, "history", c.i.tenant, c.tenant()).toString())
        .doesNotContain("PRIVATE_PROCUREMENT_NOTE");
    assertThat(rows(c, "history", c.p.actor, c.job()).toString())
        .doesNotContain("PRIVATE_PROCUREMENT_NOTE");
    assertThat(node(call("GET", "/api/v1/me/notifications", null, c.i.tenant, 200)).toString())
        .doesNotContain("PRIVATE_PROCUREMENT_NOTE");
    b = cmd(c);
    b.put("visibility", "INTERNAL");
    call("POST", c.job() + "/notes", b, c.p.actor, 403);
  }

  @Test
  void evidenceIsPrivateReencodedAndSafeToRetry() throws Exception {
    var c = accepted();
    UUID key = UUID.randomUUID();
    long version = view(c).get("version").asLong();
    byte[] image = png();
    assertThat(upload(c, c.job(), c.p.actor, "SHARED", key, version, image).statusCode())
        .isEqualTo(200);
    assertThat(upload(c, c.job(), c.p.actor, "SHARED", key, version, image).statusCode())
        .isEqualTo(200);
    JsonNode images = rows(c, "evidence", c.i.tenant, c.tenant());
    assertThat(images.size()).isEqualTo(1);
    String id = images.get(0).get("id").asText();
    assertThat(images.toString()).doesNotContain("storageKey", "private.png", ".work");
    var response =
        client.send(
            request("GET", c.tenant() + "/evidence/" + id + "/content", null, c.i.tenant),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(
            javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.body()))
                .getWidth())
        .isEqualTo(40);
    call("GET", c.tenant() + "/evidence/" + id + "/content", null, account(), 404);
    call("GET", "/api/v1/listings/" + c.i.listing + "/images/" + id, null, null, 404);
  }

  @Test
  void internalEvidenceCannotBeReadThroughKnownIdsByOtherParties() throws Exception {
    var c = accepted();
    assertThat(
            upload(
                    c,
                    c.manager(),
                    c.i.owner,
                    "INTERNAL",
                    UUID.randomUUID(),
                    view(c).get("version").asLong(),
                    png())
                .statusCode())
        .isEqualTo(200);
    JsonNode image = rows(c, "evidence", c.i.owner, c.manager()).get(0);
    String id = image.get("id").asText();
    assertThat(rows(c, "evidence", c.i.tenant, c.tenant()).size()).isZero();
    assertThat(rows(c, "evidence", c.p.actor, c.job()).size()).isZero();
    call("GET", c.tenant() + "/evidence/" + id + "/content", null, c.i.tenant, 404);
    call("GET", c.job() + "/evidence/" + id + "/content", null, c.p.actor, 404);
    assertThat(
            upload(
                    c,
                    c.job(),
                    c.p.actor,
                    "INTERNAL",
                    UUID.randomUUID(),
                    view(c).get("version").asLong(),
                    png())
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void newAssigneesDoNotReadPreviousProviderNotesAndImages() throws Exception {
    var c = accepted();
    var b = cmd(c);
    b.put("visibility", "SHARED");
    b.put("message", "PREVIOUS_PROVIDER_DISCUSSION");
    call("POST", c.job() + "/notes", b, c.p.actor, 200);
    assertThat(
            upload(
                    c,
                    c.job(),
                    c.p.actor,
                    "SHARED",
                    UUID.randomUUID(),
                    view(c).get("version").asLong(),
                    png())
                .statusCode())
        .isEqualTo(200);
    String image = rows(c, "evidence", c.i.owner, c.manager()).get(0).get("id").asText();
    String oldPath = c.job();
    Account old = c.p.actor;
    call("POST", c.job() + "/release", cmd(c), c.p.actor, 200);
    Provider replacement = provider();
    assign(c, replacement);
    call("POST", c.job() + "/accept", cmd(c), replacement.actor, 200);
    call("GET", oldPath, null, old, 404);
    assertThat(rows(c, "history", replacement.actor, c.job()).toString())
        .doesNotContain("PREVIOUS_PROVIDER_DISCUSSION");
    call("GET", c.job() + "/evidence/" + image + "/content", null, replacement.actor, 404);
  }

  @Test
  void invalidOrExcessEvidenceDoesNotWriteFiles() throws Exception {
    var c = maintenance();
    long before = fileCount();
    assertThat(
            upload(
                    c,
                    c.tenant(),
                    c.i.tenant,
                    "SHARED",
                    UUID.randomUUID(),
                    view(c).get("version").asLong(),
                    "fake png".getBytes(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(400);
    assertThat(fileCount()).isEqualTo(before);
    jdbc.update(
        "INSERT INTO"
            + " work_evidence(id,work_id,actor_id,party,visibility,caption,storage_key,content_type,width,height,byte_size)"
            + " SELECT gen_random_uuid(),?,?,'CUSTOMER','SHARED','Seed"
            + " capacity',gen_random_uuid()::text||'.png','image/png',1,1,1 FROM"
            + " generate_series(1,30)",
        UUID.fromString(c.id),
        c.i.tenant.id);
    assertThat(
            upload(
                    c,
                    c.tenant(),
                    c.i.tenant,
                    "SHARED",
                    UUID.randomUUID(),
                    view(c).get("version").asLong(),
                    png())
                .statusCode())
        .isEqualTo(409);
    assertThat(fileCount()).isEqualTo(before);
  }

  @Test
  void failedAuditRollsBackEvidenceFileMetadataAndOperationKey() throws Exception {
    var c = accepted();
    String constraint = constraint();
    long before = fileCount();
    long version = view(c).get("version").asLong();
    UUID key = UUID.randomUUID();
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'WORK_EVIDENCE_ADDED') NOT VALID");
    try {
      assertThat(upload(c, c.job(), c.p.actor, "SHARED", key, version, png()).statusCode())
          .isEqualTo(409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(fileCount()).isEqualTo(before);
    assertThat(rows(c, "evidence", c.i.owner, c.manager()).size()).isZero();
    assertThat(upload(c, c.job(), c.p.actor, "SHARED", key, version, png()).statusCode())
        .isEqualTo(200);
  }

  @Test
  void failedNotificationRollsBackWorkAndItsRetryKey() throws Exception {
    var c = maintenance();
    var b = cmd(c);
    b.put("priority", "HIGH");
    String constraint = constraint();
    jdbc.execute(
        "ALTER TABLE in_app_notifications ADD CONSTRAINT "
            + constraint
            + " CHECK(work_event_id IS NULL) NOT VALID");
    try {
      call("POST", c.manager() + "/triage", b, c.i.owner, 409);
    } finally {
      jdbc.execute("ALTER TABLE in_app_notifications DROP CONSTRAINT " + constraint);
    }
    assertThat(view(c).get("status").asText()).isEqualTo("SUBMITTED");
    call("POST", c.manager() + "/triage", b, c.i.owner, 200);
  }

  @Test
  void concurrentStaleTriageHasOneWinnerAndExactRetriesDoNotAppendEvents() throws Exception {
    var c = maintenance();
    var first = cmd(c);
    first.put("priority", "HIGH");
    var second = new HashMap<>(first);
    second.put("requestId", UUID.randomUUID());
    var a =
        client.sendAsync(
            request("POST", c.manager() + "/triage", first, c.i.owner),
            HttpResponse.BodyHandlers.ofString());
    var b =
        client.sendAsync(
            request("POST", c.manager() + "/triage", second, c.i.owner),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    var winner = a.get().statusCode() == 200 ? first : second;
    call("POST", c.manager() + "/triage", winner, c.i.owner, 200);
    assertThat(rows(c, "history", c.i.owner, c.manager()).size()).isEqualTo(2);
    var changed = new HashMap<>(winner);
    changed.put("priority", "LOW");
    call("POST", c.manager() + "/triage", changed, c.i.owner, 409);
  }

  @Test
  void workHistoryAndIdentityCannotBeRewritten() throws Exception {
    var c = assigned();
    UUID id = UUID.fromString(c.id);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE work_requests SET description='rewritten' WHERE id=?", id))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM work_assignments WHERE work_id=?", id))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE work_events SET message='changed' WHERE work_id=?", id))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void summaryAndPaginationRespectParticipantScopes() throws Exception {
    var c = assigned();
    assertThat(
            node(call("GET", "/api/v1/me/work-requests/summary", null, c.i.tenant, 200))
                .get("open")
                .asInt())
        .isEqualTo(1);
    assertThat(
            node(call("GET", c.i.propertyPath() + "/work-requests/summary", null, c.i.owner, 200))
                .get("total")
                .asInt())
        .isEqualTo(1);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + c.p.org + "/service-jobs/summary",
                    null,
                    c.p.actor,
                    200))
                .get("total")
                .asInt())
        .isEqualTo(1);
    call("GET", c.tenant() + "/history?limit=101", null, c.i.tenant, 400);
    call("GET", "/api/v1/me/work-requests?offset=-1", null, c.i.tenant, 400);
  }

  @Test
  void leaseCheckoutWaitsForStartedMaintenanceButClosureCanProceed() throws Exception {
    var c = started();
    clock.days(31);
    loginAll(c);
    JsonNode lease =
        node(
            call(
                "POST",
                c.i.path(c.lease) + "/complete",
                reason(c.lease, "Lease term has ended."),
                c.i.owner,
                200));
    var release =
        Map.of(
            "version",
            lease.get("version").asLong(),
            "vacancyConfirmed",
            true,
            "message",
            "Vacancy inspection completed.");
    call("POST", c.i.path(lease) + "/release-unit", release, c.i.owner, 409);
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    call("POST", c.i.path(lease) + "/release-unit", release, c.i.owner, 200);
  }

  @Test
  void bookingCannotStartAfterLeaseAccessIsReleased() throws Exception {
    var c = booking(provider());
    call("POST", c.job() + "/accept", cmd(c), c.p.actor, 200);
    String q = quote(c);
    call("POST", c.tenant() + "/quotes/" + q + "/accept", consent(c), c.i.tenant, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    clock.days(31);
    loginAll(c);
    JsonNode lease =
        node(
            call(
                "POST",
                c.i.path(c.lease) + "/complete",
                reason(c.lease, "Term expired before service visit."),
                c.i.owner,
                200));
    call(
        "POST",
        c.i.path(lease) + "/release-unit",
        Map.of(
            "version",
            lease.get("version").asLong(),
            "vacancyConfirmed",
            true,
            "message",
            "Unit confirmed vacant."),
        c.i.owner,
        200);
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 409);
    call("POST", c.job() + "/release", cmd(c), c.p.actor, 200);
    call("GET", c.tenant(), null, c.i.tenant, 200);
  }

  @Test
  void changingTheQuoteInvalidatesPendingVisitConsent() throws Exception {
    var c = accepted();
    String first = quote(c);
    call("POST", c.manager() + "/quotes/" + first + "/accept", consent(c), c.i.owner, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    String oldVisit = view(c).get("visitId").asText();
    quote(c);
    assertThat(view(c).get("visitId").isNull()).isTrue();
    call("POST", c.tenant() + "/visits/" + oldVisit + "/accept", consent(c), c.i.tenant, 409);
  }

  @Test
  void cancellationRevokesPendingProviderAccessAndNeverRevealsTheAddress() throws Exception {
    var c = assigned();
    call("POST", c.manager() + "/cancel", cmd(c), c.i.owner, 200);
    assertThat(view(c).get("status").asText()).isEqualTo("CANCELLED");
    call("GET", c.job(), null, c.p.actor, 404);
    assertThat(node(call("GET", "/api/v1/me/notifications", null, c.p.actor, 200)).toString())
        .contains("The work assignment was ended.")
        .doesNotContain("Private lease premises");
  }

  @Test
  void coordinatorConsoleFiltersNeverExposePersonalBookingsOrAnotherProperty() throws Exception {
    var c = maintenance();
    Provider p = provider();
    JsonNode booking =
        node(
            call(
                "POST",
                "/api/v1/me/work-requests",
                createBody(c.lease, "SERVICE_BOOKING", p),
                c.i.tenant,
                200));
    String base = c.i.propertyPath() + "/work-requests";
    JsonNode rows =
        node(
            call(
                "GET",
                base + "?kind=MAINTENANCE&status=SUBMITTED&priority=NORMAL&limit=20&offset=0",
                null,
                c.i.owner,
                200));
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).get("id").asText()).isEqualTo(c.id);
    assertThat(rows.get(0).get("propertyId").asText()).isEqualTo(c.i.property);
    assertThat(rows.toString()).doesNotContain(booking.get("id").asText());
    call("GET", base + "/" + booking.get("id").asText(), null, c.i.owner, 404);
    call("GET", base + "?limit=20&offset=20", null, c.i.owner, 200);
    Account unrelated = account();
    call("GET", base, null, unrelated, 404);
  }

  @Test
  void coordinatorConsoleInternalNotesRemainPrivateAndRevocationBlocksFrozenRetry()
      throws Exception {
    var c = maintenance();
    Account agent = account();
    String agency = organization(agent, "AGENCY");
    JsonNode property = node(call("GET", c.i.propertyPath(), null, c.i.owner, 200));
    call(
        "PATCH",
        c.i.propertyPath() + "/management",
        Map.of("version", property.get("version").asLong(), "managerOrganizationId", agency),
        c.i.owner,
        200);
    String scoped =
        "/api/v1/organizations/"
            + agency
            + "/properties/"
            + c.i.property
            + "/work-requests/"
            + c.id;
    var note = cmd(c);
    note.put("visibility", "INTERNAL");
    note.put("message", "Private coordinator procurement discussion.");
    call("POST", scoped + "/notes", note, agent, 200);
    call("POST", scoped + "/notes", note, agent, 200);
    assertThat(call("GET", scoped + "/history", null, agent, 200).body())
        .contains("Private coordinator procurement discussion.");
    assertThat(call("GET", c.tenant() + "/history", null, c.i.tenant, 200).body())
        .doesNotContain("Private coordinator procurement discussion.");
    property = node(call("GET", c.i.propertyPath(), null, c.i.owner, 200));
    var revoke = new HashMap<String, Object>();
    revoke.put("version", property.get("version").asLong());
    revoke.put("managerOrganizationId", null);
    call("PATCH", c.i.propertyPath() + "/management", revoke, c.i.owner, 200);
    call("GET", scoped, null, agent, 404);
    call("POST", scoped + "/notes", note, agent, 404);
  }

  @Test
  void coordinatorConsoleInternalWorkCannotSkipTenantEntryOrCompletionReview() throws Exception {
    var c = maintenance();
    triage(c);
    call("POST", c.manager() + "/assign-internal", cmd(c), c.i.owner, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    call("POST", c.manager() + "/start", cmd(c), c.i.owner, 409);
    acceptVisit(c);
    clock.now = clock.instant().plusSeconds(3600);
    loginAll(c);
    call("POST", c.manager() + "/start", cmd(c), c.i.owner, 200);
    var completion = cmd(c);
    completion.remove("message");
    completion.put("summary", "Internal team repaired and tested the kitchen tap.");
    call("POST", c.manager() + "/submit-completion", completion, c.i.owner, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 409);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    JsonNode result =
        node(call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200));
    assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
  }

  private Scenario maintenance() throws Exception {
    Inventory i = inventory();
    JsonNode lease = active(i);
    var body = createBody(lease, "MAINTENANCE", null);
    JsonNode w = node(call("POST", "/api/v1/me/work-requests", body, i.tenant, 200));
    return new Scenario(i, lease, null, w.get("id").asText());
  }

  private Scenario booking(Provider p) throws Exception {
    Inventory i = inventory();
    JsonNode lease = executed(i);
    JsonNode w =
        node(
            call(
                "POST",
                "/api/v1/me/work-requests",
                createBody(lease, "SERVICE_BOOKING", p),
                i.tenant,
                200));
    return new Scenario(i, lease, p, w.get("id").asText());
  }

  private Scenario assigned() throws Exception {
    Scenario c = maintenance();
    c.p = provider();
    triage(c);
    assign(c, c.p);
    return c;
  }

  private Scenario accepted() throws Exception {
    var c = assigned();
    call("POST", c.job() + "/accept", cmd(c), c.p.actor, 200);
    return c;
  }

  private Scenario scheduled() throws Exception {
    var c = accepted();
    String q = quote(c);
    call("POST", c.manager() + "/quotes/" + q + "/accept", consent(c), c.i.owner, 200);
    propose(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200));
    acceptVisit(c);
    return c;
  }

  private Scenario started() throws Exception {
    var c = scheduled();
    clock.now = clock.instant().plusSeconds(3600);
    loginAll(c);
    call("POST", c.job() + "/start", cmd(c), c.p.actor, 200);
    return c;
  }

  private Scenario completed() throws Exception {
    var c = started();
    submit(c);
    call("POST", c.tenant() + "/confirm-completion", consent(c), c.i.tenant, 200);
    call("POST", c.manager() + "/approve-completion", consent(c), c.i.owner, 200);
    return c;
  }

  private Provider provider() throws Exception {
    return provider("Nairobi", "PLUMBING");
  }

  private Provider provider(String city, String category) throws Exception {
    Account actor = account();
    String org = organization(actor, "SERVICE_PROVIDER");
    String base = "/api/v1/organizations/" + org + "/service-profile";
    call("POST", base, profile(city), actor, 200);
    JsonNode offer = node(call("POST", base + "/offers", offerDetails(category), actor, 200));
    String id = offer.get("id").asText();
    call(
        "POST",
        base + "/offers/" + id + "/state",
        Map.of("version", 0, "status", "ACTIVE"),
        actor,
        200);
    call("POST", base + "/state", Map.of("version", 0, "status", "ACTIVE"), actor, 200);
    return new Provider(actor, org, id);
  }

  private Map<String, Object> profile(String city) {
    return Map.of(
        "displayName",
        "Repairs team",
        "description",
        "Registered business service profile supplied for customers.",
        "areas",
        List.of(Map.of("countryCode", "KE", "city", city)));
  }

  private Map<String, Object> offerDetails(String category) {
    return Map.of(
        "category",
        category,
        "title",
        "Service inspection and repair",
        "description",
        "Inspection and repair scope to be agreed in a job quote.",
        "priceUnit",
        "QUOTE_REQUIRED",
        "currency",
        "KES");
  }

  private HashMap<String, Object> createBody(JsonNode lease, String kind, Provider provider) {
    var b = new HashMap<String, Object>();
    b.put("requestId", UUID.randomUUID());
    b.put("leaseId", lease.get("id").asText());
    b.put("kind", kind);
    b.put("category", "PLUMBING");
    b.put("priority", "NORMAL");
    b.put("title", "Leaking tap");
    b.put("description", "Kitchen tap has been leaking and needs a qualified inspection.");
    if (provider != null) {
      b.put("offerId", provider.offer);
      b.put("offerVersion", 1);
    }
    return b;
  }

  private JsonNode view(Scenario c) throws Exception {
    return node(call("GET", c.tenant(), null, c.i.tenant, 200));
  }

  private HashMap<String, Object> cmd(Scenario c) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            view(c).get("version").asLong(),
            "message",
            "Reviewed request details and confirmed this action."));
  }

  private HashMap<String, Object> consent(Scenario c) throws Exception {
    var b = cmd(c);
    b.put("consent", true);
    return b;
  }

  private void triage(Scenario c) throws Exception {
    var b = cmd(c);
    b.put("priority", "HIGH");
    call("POST", c.manager() + "/triage", b, c.i.owner, 200);
  }

  private void assign(Scenario c, Provider p) throws Exception {
    var b = cmd(c);
    b.put("offerId", p.offer);
    b.put("offerVersion", 1);
    call("POST", c.manager() + "/assign", b, c.i.owner, 200);
    c.p = p;
  }

  private String quote(Scenario c) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("amount", "1500.00");
    b.put("currency", "KES");
    b.put("validUntil", clock.instant().plus(Duration.ofDays(2)).toString());
    b.put("scope", "Inspect kitchen plumbing and replace the specified tap fitting.");
    call("POST", c.job() + "/quotes", b, c.p.actor, 200);
    return view(c).get("quoteId").asText();
  }

  private void propose(Scenario c, Instant start, Instant end) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("startAt", start.toString());
    b.put("endAt", end.toString());
    b.put("accessInstructions", "Ring the bell; the occupant will meet the technician.");
    call(
        "POST",
        c.p == null ? c.manager() + "/visits" : c.job() + "/visits",
        b,
        c.p == null ? c.i.owner : c.p.actor,
        200);
  }

  private void acceptVisit(Scenario c) throws Exception {
    call(
        "POST",
        c.tenant() + "/visits/" + view(c).get("visitId").asText() + "/accept",
        consent(c),
        c.i.tenant,
        200);
  }

  private void submit(Scenario c) throws Exception {
    var b = cmd(c);
    b.remove("message");
    b.put("summary", "Inspected the tap, replaced the fitting, and checked for leaks.");
    call(
        "POST",
        c.p == null ? c.manager() + "/submit-completion" : c.job() + "/submit-completion",
        b,
        c.p == null ? c.i.owner : c.p.actor,
        200);
  }

  private void loginAll(Scenario c) throws Exception {
    login(c.i.owner);
    login(c.i.tenant);
    if (c.p != null) login(c.p.actor);
  }

  private JsonNode rows(Scenario c, String resource, Account actor, String base) throws Exception {
    return node(call("GET", base + "/" + resource, null, actor, 200));
  }

  private byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
    try (var out = new java.io.ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(image, "png", out);
      image.flush();
      return out.toByteArray();
    }
  }

  private HttpResponse<String> upload(
      Scenario c,
      String base,
      Account actor,
      String visibility,
      UUID key,
      long version,
      byte[] image)
      throws Exception {
    String boundary = "work" + UUID.randomUUID().toString().replace("-", "");
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
                key.toString(),
                "version",
                String.valueOf(version),
                "visibility",
                visibility,
                "caption",
                "Private repair photograph")
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
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + base + "/evidence"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + actor.token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private long fileCount() throws Exception {
    try (var files = java.nio.file.Files.list(IMAGE_ROOT)) {
      return files.count();
    }
  }

  private static class Scenario {
    final Inventory i;
    final JsonNode lease;
    Provider p;
    final String id;

    Scenario(Inventory i, JsonNode lease, Provider p, String id) {
      this.i = i;
      this.lease = lease;
      this.p = p;
      this.id = id;
    }

    String tenant() {
      return "/api/v1/me/work-requests/" + id;
    }

    String manager() {
      return i.propertyPath() + "/work-requests/" + id;
    }

    String job() {
      return "/api/v1/organizations/" + p.org + "/service-jobs/" + id;
    }
  }

  private record Provider(Account actor, String org, String offer) {
    String profile() {
      return "/api/v1/organizations/" + org + "/service-profile";
    }

    String directory() {
      return "/api/v1/service-providers/" + org;
    }
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
  static class TestClockConfiguration {
    @Bean
    @Primary
    WorkTestClock leaseClock() {
      return new WorkTestClock();
    }
  }

  static class WorkTestClock extends Clock {
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
