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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zaza.communication.messages-per-minute=4",
      "zaza.communication.enquiries-per-hour=2"
    })
@ActiveProfiles("test")
class InspectionTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired InspectionTestClock clock;
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

  @Test
  void trackingIsExplicitAndCannotBeDisabledOrEnabledOnDrafts() throws Exception {
    var i = inventory();
    var draft = draft(i);
    call(
        "POST",
        i.path(draft) + "/inspections/enable",
        Map.of("requestId", UUID.randomUUID(), "leaseVersion", draft.get("version").asLong()),
        i.owner,
        409);
    var lease = send(i, draft);
    lease = sign(i, lease, true);
    lease = sign(i, lease, false);
    var b = enableBody(lease);
    var path = i.path(lease) + "/inspections/enable";
    assertThat(node(call("POST", path, b, i.owner, 200)).get("moveInRequired").asBoolean())
        .isTrue();
    call("POST", path, b, i.owner, 200);
    call("POST", path, enableBody(lease), i.owner, 409);
    assertThat(count("lease_inspection_policies", "lease_id", lease.get("id").asText()))
        .isEqualTo(1);
  }

  @Test
  void trackingRequiresCurrentLeaseVersionAndCreatesOnePolicy() throws Exception {
    var i = inventory();
    var lease = executed(i);
    var b = enableBody(lease);
    b.put("leaseVersion", 999);
    call("POST", i.path(lease) + "/inspections/enable", b, i.owner, 409);
    assertThat(count("lease_inspection_policies", "lease_id", lease.get("id").asText())).isZero();
    call("POST", i.path(lease) + "/inspections/enable", enableBody(lease), i.owner, 200);
  }

  @Test
  void untrackedLeasesRetainExistingActivationAndCheckoutContract() throws Exception {
    var i = inventory();
    var lease = active(i);
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, i.tenant, 200))
                .get("inspectionTrackingEnabled")
                .asBoolean())
        .isFalse();
    clock.days(30);
    refresh(i);
    lease = manager(i, lease);
    lease =
        node(call("POST", i.path(lease) + "/complete", reason(lease, "Term ended."), i.owner, 200));
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, i.tenant, 200))
                .get("readyToRelease")
                .asBoolean())
        .isTrue();
    release(i, lease, 200);
  }

  @Test
  void moveInAcceptanceGatesActivationAndAcceptedReportCannotChange() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    call(
        "POST",
        f.i.path(f.lease) + "/activate",
        Map.of("version", f.lease.get("version").asLong(), "possessionConfirmed", true),
        f.i.owner,
        409);
    r = share(f, r);
    r = accept(f, r);
    activate(f);
    var change =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            r.get("version").asLong(),
            "details",
            details());
    call("PUT", m(f, r), change, f.i.owner, 409);
    assertThat(upload(f, r, f.i.owner, UUID.randomUUID(), png()).statusCode()).isEqualTo(409);
  }

  @Test
  void managerDraftAndUnsharedRevisionsArePrivate() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    call("GET", t(f, r), null, f.i.tenant, 404);
    assertThat(node(call("GET", own(f.lease) + "/inspections", null, f.i.tenant, 200))).isEmpty();
    call(
        "GET",
        t(f, r) + "/revisions/" + r.get("revision").get("id").asText() + "/document",
        null,
        f.i.tenant,
        404);
    r = share(f, r);
    r = dispute(f, r);
    var old = r.get("revision").get("id").asText();
    r = revise(f, r, details());
    var tenant = node(call("GET", t(f, r), null, f.i.tenant, 200));
    assertThat(tenant.get("revision").get("id").asText()).isEqualTo(old);
    assertThat(node(call("GET", t(f, r) + "/revisions", null, f.i.tenant, 200)).size())
        .isEqualTo(1);
    assertThat(node(call("GET", m(f, r) + "/revisions", null, f.i.owner, 200)).size()).isEqualTo(2);
    call(
        "GET",
        t(f, r) + "/revisions/" + r.get("revision").get("id").asText() + "/document",
        null,
        f.i.tenant,
        404);
  }

  @Test
  void disputesNeedRevisedEvidenceAndOnlyTenantCanResolveThem() throws Exception {
    var f = signedTracked();
    var r = dispute(f, share(f, create(f, "MOVE_IN")));
    call("POST", m(f, r) + "/share", review(r), f.i.owner, 409);
    call("POST", t(f, r) + "/accept", review(r), f.i.tenant, 409);
    r = revise(f, r, details());
    r = share(f, r);
    r = accept(f, r);
    assertThat(r.get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(node(call("GET", t(f, r) + "/history", null, f.i.tenant, 200)).toString())
        .contains("DISPUTED", "ACCEPTED")
        .doesNotContain("REVISED", "CREATED");
  }

  @Test
  void exactDocumentRevisionConsentAndPasswordAreRequired() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var b = review(r);
    b.put("consent", false);
    call("POST", m(f, r) + "/share", b, f.i.owner, 400);
    b = review(r);
    b.put("documentHash", "0".repeat(64));
    call("POST", m(f, r) + "/share", b, f.i.owner, 409);
    b = review(r);
    b.put("revisionId", UUID.randomUUID());
    call("POST", m(f, r) + "/share", b, f.i.owner, 409);
    b = review(r);
    b.put("password", "Wrong-password-value");
    call("POST", m(f, r) + "/share", b, f.i.owner, 401);
    r = share(f, r);
    b = review(r);
    b.put("password", "Wrong-password-value");
    call("POST", t(f, r) + "/accept", b, f.i.tenant, 401);
    assertThat(count("lease_inspection_decisions", "inspection_id", r.get("id").asText())).isZero();
  }

  @Test
  void idempotencyIsBoundToActorActionTargetAndPayload() throws Exception {
    var f = signedTracked();
    var body =
        new HashMap<String, Object>(
            Map.of("requestId", UUID.randomUUID(), "kind", "MOVE_IN", "details", details()));
    var r = node(call("POST", f.i.path(f.lease) + "/inspections", body, f.i.owner, 201));
    assertThat(
            node(call("POST", f.i.path(f.lease) + "/inspections", body, f.i.owner, 201)).get("id"))
        .isEqualTo(r.get("id"));
    body.put("kind", "MOVE_OUT");
    call("POST", f.i.path(f.lease) + "/inspections", body, f.i.owner, 409);
    var share = review(r);
    r = node(call("POST", m(f, r) + "/share", share, f.i.owner, 200));
    call("POST", m(f, r) + "/share", share, f.i.owner, 200);
    share.put("reason", "A changed payload");
    call("POST", m(f, r) + "/share", share, f.i.owner, 409);
    var accept = review(r);
    r = node(call("POST", t(f, r) + "/accept", accept, f.i.tenant, 200));
    call("POST", t(f, r) + "/accept", accept, f.i.tenant, 200);
    assertThat(count("lease_inspection_decisions", "inspection_id", r.get("id").asText()))
        .isEqualTo(1);
  }

  @Test
  void concurrentRevisionWritersCannotLoseChanges() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var a =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            r.get("version").asLong(),
            "details",
            details());
    var b =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            r.get("version").asLong(),
            "details",
            details());
    var first =
        client.sendAsync(
            request("PUT", m(f, r), a, f.i.owner), HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            request("PUT", m(f, r), b, f.i.owner), HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                first.get(30, TimeUnit.SECONDS).statusCode(),
                second.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(count("lease_inspection_revisions", "inspection_id", r.get("id").asText()))
        .isEqualTo(2);
  }

  @Test
  void concurrentAcceptAndDisputeProduceOneDecision() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    var first =
        client.sendAsync(
            request("POST", t(f, r) + "/accept", review(r), f.i.tenant),
            HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            request("POST", t(f, r) + "/dispute", disputeBody(r), f.i.tenant),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                first.get(30, TimeUnit.SECONDS).statusCode(),
                second.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(count("lease_inspection_decisions", "inspection_id", r.get("id").asText()))
        .isEqualTo(1);
  }

  @Test
  void unrelatedAccountsAndPlatformAdministratorsCannotReadReports() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    var outsider = account();
    call("GET", t(f, r), null, outsider, 404);
    jdbc.update("UPDATE app_users SET platform_role='PLATFORM_ADMIN' WHERE id=?", outsider.id);
    login(outsider);
    call("GET", t(f, r), null, outsider, 404);
    call("GET", m(f, r), null, outsider, 404);
    call("GET", t(f, r), null, null, 401);
  }

  @Test
  void organizationMembersWithoutInspectionCapabilityCannotReview() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var member = account();
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
        UUID.fromString(f.i.organization),
        member.id);
    call("GET", m(f, r), null, member, 404);
    call("POST", m(f, r) + "/share", review(r), member, 404);
  }

  @Test
  void delegationHandoverRevokesOldAgencyAndAllowsCurrentAgency() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var agent = account();
    var agency = organization(agent, "AGENCY");
    var p = node(call("GET", f.i.propertyPath(), null, f.i.owner, 200));
    call(
        "PATCH",
        f.i.propertyPath() + "/management",
        Map.of("version", p.get("version").asLong(), "managerOrganizationId", agency),
        f.i.owner,
        200);
    String agencyPath = m(f, r).replace(f.i.organization, agency);
    call("GET", agencyPath, null, agent, 200);
    p = node(call("GET", f.i.propertyPath(), null, f.i.owner, 200));
    var remove = new HashMap<String, Object>();
    remove.put("version", p.get("version").asLong());
    remove.put("managerOrganizationId", null);
    call("PATCH", f.i.propertyPath() + "/management", remove, f.i.owner, 200);
    call("GET", agencyPath, null, agent, 404);
    call("POST", agencyPath + "/share", review(r), agent, 404);
  }

  @Test
  void tenantWhoIsAlsoManagerCannotAttestAsCounterparty() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role) VALUES (?,?,'ADMIN')",
        UUID.fromString(f.i.organization),
        f.i.tenant.id);
    call("POST", m(f, r) + "/share", review(r), f.i.tenant, 403);
    call(
        "PUT",
        m(f, r),
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            r.get("version").asLong(),
            "details",
            details()),
        f.i.tenant,
        403);
  }

  @Test
  void reportIdsAreScopedToTheirLease() throws Exception {
    var f = signedTracked();
    var other = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    call(
        "GET",
        other.i.path(other.lease) + "/inspections/" + r.get("id").asText(),
        null,
        other.i.owner,
        404);
    call(
        "GET",
        own(other.lease) + "/inspections/" + r.get("id").asText() + "/revisions",
        null,
        other.i.tenant,
        404);
  }

  @Test
  void nestedValidationAndDuplicateKeysRejectInvalidReports() throws Exception {
    var f = signedTracked();
    var d = details();
    d.put("items", List.of(item("wall", "GOOD"), item("wall", "DAMAGED")));
    createWith(f, "MOVE_IN", d, 400);
    d = details();
    d.put("items", List.of());
    createWith(f, "MOVE_IN", d, 400);
    d = details();
    d.put(
        "keys",
        List.of(
            Map.of("key", "front", "label", "Door", "expectedCount", -1, "transferredCount", 1)));
    createWith(f, "MOVE_IN", d, 400);
    d = details();
    d.put("observedAt", clock.instant().plusSeconds(1).toString());
    createWith(f, "MOVE_IN", d, 400);
    d = details();
    d.put(
        "meters",
        List.of(
            Map.of(
                "key",
                "water",
                "label",
                "Water",
                "serialNumber",
                "x",
                "reading",
                "-1",
                "unit",
                "m3")));
    createWith(f, "MOVE_IN", d, 400);
    assertThat(count("lease_inspections", "lease_id", f.lease.get("id").asText())).isZero();
  }

  @Test
  void evidenceReferencesMustBelongToThisReport() throws Exception {
    var f = signedTracked();
    var d = details();
    d.put("evidenceIds", List.of(UUID.randomUUID()));
    createWith(f, "MOVE_IN", d, 400);
    var r = create(f, "MOVE_IN");
    d = details();
    d.put("evidenceIds", List.of(UUID.randomUUID()));
    call(
        "PUT",
        m(f, r),
        Map.of("requestId", UUID.randomUUID(), "version", r.get("version").asLong(), "details", d),
        f.i.owner,
        400);
  }

  @Test
  void invalidAndStalePhotoUploadsDoNotCreateFiles() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    long before = fileCount();
    assertThat(
            upload(
                    f,
                    r,
                    f.i.owner,
                    UUID.randomUUID(),
                    "not-an-image".getBytes(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(400);
    var stale = r.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) stale).put("version", 999);
    assertThat(upload(f, stale, f.i.owner, UUID.randomUUID(), png()).statusCode()).isEqualTo(409);
    assertThat(fileCount()).isEqualTo(before);
  }

  @Test
  void managerPhotosStayPrivateUntilIncludedInASharedRevision() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    UUID key = UUID.randomUUID();
    var uploaded = upload(f, r, f.i.owner, key, png());
    assertThat(uploaded.statusCode()).isEqualTo(200);
    assertThat(upload(f, r, f.i.owner, key, png()).statusCode()).isEqualTo(200);
    r = node(uploaded);
    var photos = node(call("GET", m(f, r) + "/evidence", null, f.i.owner, 200));
    var image = photos.get(0).get("id").asText();
    assertThat(photos.toString()).doesNotContain("storageKey", "uploaderId");
    var d = details();
    d.put("evidenceIds", List.of(image));
    r = share(f, revise(f, r, d));
    assertThat(node(call("GET", t(f, r) + "/evidence", null, f.i.tenant, 200)).size()).isEqualTo(1);
    var content = call("GET", t(f, r) + "/evidence/" + image + "/content", null, f.i.tenant, 200);
    assertThat(content.headers().firstValue("cache-control").orElse("")).contains("no-store");
    assertThat(content.headers().firstValue("x-content-type-options").orElse(""))
        .isEqualTo("nosniff");
    call("GET", t(f, r) + "/evidence/" + image + "/content", null, account(), 404);
  }

  @Test
  void unpublishedManagerPhotosRemainHiddenDuringDisputes() throws Exception {
    var f = signedTracked();
    var r = dispute(f, share(f, create(f, "MOVE_IN")));
    var uploaded = upload(f, r, f.i.owner, UUID.randomUUID(), png());
    assertThat(uploaded.statusCode()).isEqualTo(200);
    r = node(uploaded);
    var image =
        node(call("GET", m(f, r) + "/evidence", null, f.i.owner, 200)).get(0).get("id").asText();
    assertThat(node(call("GET", t(f, r) + "/evidence", null, f.i.tenant, 200))).isEmpty();
    call("GET", t(f, r) + "/evidence/" + image + "/content", null, f.i.tenant, 404);
  }

  @Test
  void tenantCanSupplyCounterEvidenceButCannotEditManagerReport() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    var uploaded = upload(f, r, f.i.tenant, UUID.randomUUID(), png());
    assertThat(uploaded.statusCode()).isEqualTo(200);
    r = node(uploaded);
    assertThat(
            node(call("GET", m(f, r) + "/evidence", null, f.i.owner, 200))
                .get(0)
                .get("party")
                .asText())
        .isEqualTo("TENANT");
    var evidence =
        node(call("GET", m(f, r) + "/evidence", null, f.i.owner, 200)).get(0).get("id").asText();
    r = dispute(f, r);
    var d = details();
    d.put("evidenceIds", List.of(evidence));
    r = share(f, revise(f, r, d));
    accept(f, r);
  }

  @Test
  void observationWindowFollowsAcceptedTerminationDate() throws Exception {
    var f = activeTracked();
    createWith(f, "MOVE_OUT", details(), 409);
    var lease = manager(f.i, f.lease);
    lease = requestEnd(f.i, lease, true, today());
    var termination = lease.get("termination").get("id").asText();
    lease =
        node(
            call(
                "POST",
                f.i.path(lease) + "/terminations/" + termination + "/accept",
                acceptBody(lease, false),
                f.i.owner,
                200));
    var r = create(f, "MOVE_OUT");
    r = accept(f, share(f, r));
    lease = manager(f.i, lease);
    lease =
        node(
            call(
                "POST",
                f.i.path(lease) + "/complete",
                reason(lease, "Mutual checkout."),
                f.i.owner,
                200));
    assertThat(lease.get("status").asText()).isEqualTo("TERMINATED");
    release(f.i, lease, 200);
  }

  @Test
  void activeLeaseCanEnrollForMoveOutWithoutInventingMoveInBaseline() throws Exception {
    var i = inventory();
    var lease = active(i);
    var status =
        node(call("POST", i.path(lease) + "/inspections/enable", enableBody(lease), i.owner, 200));
    assertThat(status.get("moveInRequired").asBoolean()).isFalse();
    var f = new InspectionFixture(i, lease);
    createWith(f, "MOVE_IN", details(), 409);
    clock.days(30);
    refresh(i);
    accept(f, share(f, create(f, "MOVE_OUT")));
    lease = manager(i, lease);
    lease =
        node(
            call(
                "POST",
                i.path(lease) + "/complete",
                reason(lease, "Legacy enrolled checkout."),
                i.owner,
                200));
    release(i, lease, 200);
  }

  @Test
  void moveOutRequiresVacancyConsentFromBothParties() throws Exception {
    var f = lateTracked();
    var r = create(f, "MOVE_OUT");
    var b = review(r);
    b.put("vacancyConfirmed", false);
    call("POST", m(f, r) + "/share", b, f.i.owner, 400);
    r = share(f, r);
    b = review(r);
    b.put("vacancyConfirmed", false);
    call("POST", t(f, r) + "/accept", b, f.i.tenant, 400);
    var lease = manager(f.i, f.lease);
    call("POST", f.i.path(lease) + "/complete", reason(lease, "End"), f.i.owner, 409);
  }

  @Test
  void disputedMoveOutBlocksCompletionAndCannotReleaseTheUnit() throws Exception {
    var f = lateTracked();
    var r = dispute(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    call("POST", f.i.path(lease) + "/complete", reason(lease, "End"), f.i.owner, 409);
    var status = node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200));
    assertThat(status.get("blockingSteps").toString()).contains("ACCEPT_MOVE_OUT_INSPECTION");
    r = accept(f, share(f, revise(f, r, details())));
    lease = node(call("POST", f.i.path(lease) + "/complete", reason(lease, "End"), f.i.owner, 200));
    assertThat(unit(f.i).get("availability").asText()).isEqualTo("UNAVAILABLE");
    release(f.i, lease, 200);
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200))
                .get("unitReleased")
                .asBoolean())
        .isTrue();
  }

  @Test
  void moveOutDoesNotBypassAgreedEndDate() throws Exception {
    var f = activeTracked();
    clock.days(23);
    refresh(f.i);
    accept(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    call("POST", f.i.path(lease) + "/complete", reason(lease, "Too soon"), f.i.owner, 409);
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200))
                .get("blockingSteps")
                .toString())
        .contains("CLOSE_LEASE_AT_AGREED_END");
  }

  @Test
  void comparisonUsesAcceptedBaselineAndSharedMoveOut() throws Exception {
    var f = lateTracked();
    var r = create(f, "MOVE_OUT");
    var d = details();
    d.put("items", List.of(item("wall", "DAMAGED"), item("door", "MISSING")));
    r = revise(f, r, d);
    call("GET", own(f.lease) + "/inspections/comparison", null, f.i.tenant, 409);
    r = share(f, r);
    var comparison =
        node(call("GET", own(f.lease) + "/inspections/comparison", null, f.i.tenant, 200));
    assertThat(comparison.get("currency").asText()).isEqualTo("KES");
    assertThat(comparison.get("items").size()).isEqualTo(2);
    assertThat(comparison.get("items").get(1).get("changed").asBoolean()).isTrue();
    assertThat(comparison.toString()).contains("DAMAGED", "GOOD");
  }

  @Test
  void inspectionEstimatesDoNotCreateInvoicesOrDepositDeductions() throws Exception {
    var f = lateTracked();
    var r = accept(f, share(f, create(f, "MOVE_OUT")));
    assertThat(count("billing_accounts", "lease_id", f.lease.get("id").asText())).isZero();
    assertThat(
            node(call("GET", own(f.lease) + "/checkout", null, f.i.tenant, 200))
                .get("billingReady")
                .asBoolean())
        .isTrue();
  }

  @Test
  void outstandingBillingStillBlocksUnitReleaseAfterAcceptedInspection() throws Exception {
    var f = activeTracked();
    call(
        "POST",
        f.i.path(f.lease) + "/billing",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "leaseVersion",
            f.lease.get("version").asLong(),
            "policy",
            "CALENDAR_ACTUAL_DAYS"),
        f.i.owner,
        200);
    clock.days(30);
    refresh(f.i);
    accept(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    lease = node(call("POST", f.i.path(lease) + "/complete", reason(lease, "End"), f.i.owner, 200));
    release(f.i, lease, 409);
    var status = node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200));
    assertThat(status.get("inspectionsReady").asBoolean()).isTrue();
    assertThat(status.get("billingReady").asBoolean()).isFalse();
  }

  @Test
  void finalReportsRemainReadableAfterReleaseAndResponsesArePrivate() throws Exception {
    var f = lateTracked();
    var r = accept(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    lease = node(call("POST", f.i.path(lease) + "/complete", reason(lease, "End"), f.i.owner, 200));
    release(f.i, lease, 200);
    var result = call("GET", t(f, r), null, f.i.tenant, 200);
    assertThat(result.headers().firstValue("cache-control").orElse("")).contains("no-store");
    call(
        "GET",
        t(f, r) + "/revisions/" + r.get("revision").get("id").asText() + "/document",
        null,
        f.i.tenant,
        200);
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200))
                .get("readyToRelease")
                .asBoolean())
        .isFalse();
  }

  @Test
  void reportDocumentsAndHistoryCannotBeOverwrittenOrTruncated() throws Exception {
    var f = signedTracked();
    var r = accept(f, share(f, create(f, "MOVE_IN")));
    UUID id = UUID.fromString(r.get("id").asText());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE lease_inspection_revisions SET document='altered' WHERE"
                        + " inspection_id=?",
                    id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM lease_inspection_decisions WHERE inspection_id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE lease_inspections SET version=version+1 WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE lease_inspection_events"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM lease_inspection_policies WHERE lease_id=?",
                    UUID.fromString(f.lease.get("id").asText())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void auditFailureRollsBackDecisionAndCanRetryTheSameRequest() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    String constraint = constraint();
    var body = review(r);
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'INSPECTION_ACCEPTED') NOT VALID");
    try {
      call("POST", t(f, r) + "/accept", body, f.i.tenant, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(count("lease_inspection_decisions", "inspection_id", r.get("id").asText())).isZero();
    assertThat(node(call("GET", t(f, r), null, f.i.tenant, 200)).get("status").asText())
        .isEqualTo("AWAITING_TENANT");
    call("POST", t(f, r) + "/accept", body, f.i.tenant, 200);
  }

  @Test
  void auditFailureRemovesUncommittedPhotos() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    String constraint = constraint();
    long before = fileCount();
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'INSPECTION_EVIDENCE_ADDED') NOT VALID");
    try {
      assertThat(upload(f, r, f.i.owner, UUID.randomUUID(), png()).statusCode()).isEqualTo(409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(fileCount()).isEqualTo(before);
    assertThat(count("lease_inspection_evidence", "inspection_id", r.get("id").asText())).isZero();
    assertThat(node(call("GET", m(f, r), null, f.i.owner, 200)).get("version").asLong()).isZero();
  }

  @Test
  void notificationsNeverIncludePrivateReportReasonsOrPasswords() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var b = review(r);
    b.put("reason", "Private damage and key evidence");
    r = node(call("POST", m(f, r) + "/share", b, f.i.owner, 200));
    var notifications = node(call("GET", "/api/v1/me/notifications", null, f.i.tenant, 200));
    assertThat(notifications.toString()).doesNotContain("Private damage", PASSWORD, "Meter serial");
    var operations =
        jdbc.queryForList(
            "SELECT * FROM lease_inspection_operations WHERE lease_id=?",
            UUID.fromString(f.lease.get("id").asText()));
    assertThat(operations.toString()).doesNotContain(PASSWORD, "password");
    var events =
        jdbc.queryForList(
            "SELECT * FROM audit_events WHERE entity_id=?", UUID.fromString(r.get("id").asText()));
    assertThat(events.toString()).doesNotContain(PASSWORD);
  }

  @Test
  void historyAndRevisionPaginationRejectInvalidBounds() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    call("GET", t(f, r) + "/history?limit=101", null, f.i.tenant, 400);
    call("GET", t(f, r) + "/revisions?offset=-1", null, f.i.tenant, 400);
    call("GET", m(f, r) + "/evidence?limit=0", null, f.i.owner, 400);
  }

  @Test
  void signedLeaseNeverActivatedCanCloseWithBilateralMoveOutHandover() throws Exception {
    var f = signedTracked();
    clock.days(30);
    refresh(f.i);
    var r = accept(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    lease =
        node(
            call(
                "POST",
                f.i.path(lease) + "/complete",
                reason(lease, "No occupation; both parties confirmed vacant handover."),
                f.i.owner,
                200));
    release(f.i, lease, 200);
    assertThat(
            node(call("GET", own(lease) + "/checkout", null, f.i.tenant, 200))
                .get("unitReleased")
                .asBoolean())
        .isTrue();
  }

  @Test
  void acceptedMoveOutCannotBeReusedToActivateTheLease() throws Exception {
    var f = signedTracked();
    accept(f, share(f, create(f, "MOVE_IN")));
    clock.days(23);
    refresh(f.i);
    accept(f, share(f, create(f, "MOVE_OUT")));
    var lease = manager(f.i, f.lease);
    call(
        "POST",
        f.i.path(lease) + "/activate",
        Map.of("version", lease.get("version").asLong(), "possessionConfirmed", true),
        f.i.owner,
        409);
  }

  @Test
  void attestationReceiptsPreserveExactHashesMethodsAndVacancyConsent() throws Exception {
    var f = lateTracked();
    var r = accept(f, share(f, create(f, "MOVE_OUT")));
    var receipts = node(call("GET", t(f, r) + "/attestations", null, f.i.tenant, 200));
    assertThat(receipts.size()).isEqualTo(2);
    for (var receipt : receipts) {
      assertThat(receipt.get("documentHash").asText())
          .isEqualTo(r.get("revision").get("documentHash").asText());
      assertThat(receipt.get("method").asText()).isEqualTo("ACCOUNT_PASSWORD_CONSENT");
      assertThat(receipt.get("vacancyConfirmed").asBoolean()).isTrue();
    }
    assertThat(receipts.toString()).doesNotContain("password", "actorId");
  }

  @Test
  void invalidDisputeKeysCannotRecordADecision() throws Exception {
    var f = signedTracked();
    var r = share(f, create(f, "MOVE_IN"));
    var body = new HashMap<>(disputeBody(r));
    body.put("itemKeys", List.of("missing-item"));
    call("POST", t(f, r) + "/dispute", body, f.i.tenant, 400);
    body.put("itemKeys", List.of("wall", "wall"));
    call("POST", t(f, r) + "/dispute", body, f.i.tenant, 400);
    assertThat(count("lease_inspection_decisions", "inspection_id", r.get("id").asText())).isZero();
  }

  @Test
  void failureToAuditShareRollsBackPublicationAndNotification() throws Exception {
    var f = signedTracked();
    var r = create(f, "MOVE_IN");
    var body = review(r);
    String constraint = constraint();
    long before =
        jdbc.queryForObject(
            "SELECT count(*) FROM in_app_notifications WHERE user_id=?", Long.class, f.i.tenant.id);
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'INSPECTION_SHARED') NOT VALID");
    try {
      call("POST", m(f, r) + "/share", body, f.i.owner, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(count("lease_inspection_shares", "inspection_id", r.get("id").asText())).isZero();
    call("GET", t(f, r), null, f.i.tenant, 404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM in_app_notifications WHERE user_id=?",
                Long.class,
                f.i.tenant.id))
        .isEqualTo(before);
    call("POST", m(f, r) + "/share", body, f.i.owner, 200);
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
  static class InspectionClockConfiguration {
    @Bean
    @Primary
    InspectionTestClock leaseClock() {
      return new InspectionTestClock();
    }
  }

  static class InspectionTestClock extends Clock {
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
