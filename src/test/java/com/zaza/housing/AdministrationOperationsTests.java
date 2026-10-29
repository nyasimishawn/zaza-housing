package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AdministrationOperationsTests extends AdministrationTestSupport {
  @Test
  void accountRestrictionsRequireAnotherOperatorAndImmediatelyRevokeSessions() throws Exception {
    var first = user(true);
    var second = user(true);
    var target = user();
    var request =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "userId",
            target.id(),
            "version",
            0,
            "action",
            "RESTRICT",
            "reason",
            "Reviewed independent evidence of an account policy violation.",
            "password",
            PASSWORD);
    var proposal = ok("POST", OPS + "/account-actions", request, first.token(), 200);
    String id = proposal.get("id").asText();
    assertThat(ok("POST", OPS + "/account-actions", request, first.token(), 200).get("id").asText())
        .isEqualTo(id);
    ok("POST", OPS + "/account-actions/" + id + "/approve", command(proposal), first.token(), 403);
    ok("GET", "/api/v1/me", null, target.token(), 200);
    var approved =
        ok(
            "POST",
            OPS + "/account-actions/" + id + "/approve",
            command(proposal),
            second.token(),
            200);
    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    ok("GET", "/api/v1/me", null, target.token(), 401);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", target.email(), "password", PASSWORD),
        null,
        401);
    var reactivate =
        ok(
            "POST",
            OPS + "/account-actions",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "userId",
                target.id(),
                "version",
                1,
                "action",
                "REACTIVATE",
                "reason",
                "Reviewed appeal evidence and approved account restoration.",
                "password",
                PASSWORD),
            first.token(),
            200);
    ok(
        "POST",
        OPS + "/account-actions/" + reactivate.get("id").asText() + "/approve",
        command(reactivate),
        second.token(),
        200);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", target.email(), "password", PASSWORD),
        null,
        200);
    ok("GET", "/api/v1/me", null, target.token(), 401);
  }

  @Test
  void operatorAndSelfRestrictionsAreForbiddenAndVersionsAreExact() throws Exception {
    var a = user(true);
    var b = user();
    for (var target : List.of(a.id()))
      ok(
          "POST",
          OPS + "/account-actions",
          Map.of(
              "requestId",
              UUID.randomUUID(),
              "userId",
              target,
              "version",
              0,
              "action",
              "RESTRICT",
              "reason",
              "This must not disable a platform operator.",
              "password",
              PASSWORD),
          a.token(),
          403);
    ok(
        "POST",
        OPS + "/account-actions",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "userId",
            b.id(),
            "version",
            10,
            "action",
            "RESTRICT",
            "reason",
            "This stale decision must not change the user.",
            "password",
            PASSWORD),
        a.token(),
        409);
    ok("GET", OPS + "/users", null, b.token(), 403);
  }

  @Test
  void listingSuspensionBlocksDiscoveryMediaAndRepublishingUntilIndependentApproval()
      throws Exception {
    var fixture = listing();
    var a = user(true);
    String id = fixture.listing();
    var review =
        ok(
            "POST",
            OPS + "/listing-reviews",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "listingId",
                id,
                "listingVersion",
                1,
                "reason",
                "Check the current public rental information independently.",
                "password",
                PASSWORD),
            a.token(),
            200);
    review = ok("POST", OPS + "/listing-reviews/" + id + "/claim", command(review), a.token(), 200);
    var suspend = command(review);
    suspend.put("sourceVersion", 1);
    review = ok("POST", OPS + "/listing-reviews/" + id + "/suspend", suspend, a.token(), 200);
    ok("GET", "/api/v1/listings/" + id, null, null, 404);
    ok("GET", "/api/v1/listings/" + id + "/images/" + fixture.image(), null, null, 404);
    String privatePath =
        "/api/v1/organizations/"
            + fixture.org()
            + "/properties/"
            + fixture.property()
            + "/listings/"
            + id;
    var republished =
        ok(
            "POST",
            privatePath + "/publish",
            Map.of(
                "version",
                1,
                "propertyVersion",
                1,
                "unitVersion",
                0,
                "images",
                List.of(Map.of("id", fixture.image(), "version", 0))),
            fixture.owner().token(),
            200);
    assertThat(republished.get("publiclyVisible").asBoolean()).isFalse();
    review = ok("POST", OPS + "/listing-reviews/" + id + "/claim", command(review), a.token(), 200);
    var stale = command(review);
    stale.put("sourceVersion", 1);
    ok("POST", OPS + "/listing-reviews/" + id + "/approve", stale, a.token(), 409);
    review =
        ok("POST", OPS + "/listing-reviews/" + id + "/refresh", command(review), a.token(), 200);
    var approve = command(review);
    approve.put("sourceVersion", 2);
    ok("POST", OPS + "/listing-reviews/" + id + "/approve", approve, a.token(), 200);
    ok("GET", "/api/v1/listings/" + id, null, null, 200);
  }

  @Test
  void propertyMembersCannotModerateTheirOwnListing() throws Exception {
    var f = listing();
    var a = user(true);
    ok(
        "POST",
        "/api/v1/organizations/" + f.org() + "/members",
        Map.of("email", a.email(), "role", "MEMBER"),
        f.owner().token(),
        201);
    ok(
        "POST",
        OPS + "/listing-reviews",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "listingId",
            f.listing(),
            "listingVersion",
            1,
            "reason",
            "This related operator must not review the property.",
            "password",
            PASSWORD),
        a.token(),
        403);
  }

  @Test
  void auditProjectionDoesNotExposeDomainSnapshotsAndOperationalSettingsAreBounded()
      throws Exception {
    var a = user(true);
    var settings = ok("GET", OPS + "/settings", null, a.token(), 200);
    var first = settings.get(0);
    String key = first.get("key").asText();
    var r =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                first.get("version").asLong(),
                "value",
                48,
                "reason",
                "Update the agreed first-response operational target.",
                "password",
                PASSWORD));
    ok("POST", OPS + "/settings/" + key, r, a.token(), 200);
    ok("POST", OPS + "/settings/" + key, r, a.token(), 200);
    r.put("requestId", UUID.randomUUID());
    ok("POST", OPS + "/settings/" + key, r, a.token(), 409);
    var audit = ok("GET", OPS + "/audit", null, a.token(), 200);
    assertThat(audit.toString())
        .doesNotContain("passwordHash", "changes", "encryptedSecret", "publicSnapshot");
  }

  @Test
  void helpTopicsHavePrivateDraftsAndVersionedExplicitPublication() throws Exception {
    var a = user(true);
    var r =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                0,
                "slug",
                "demo-help-" + UUID.randomUUID(),
                "title",
                "Review workflow help",
                "body",
                "This is reviewed plain-text help content for the synthetic test.",
                "password",
                PASSWORD));
    var topic = ok("POST", OPS + "/knowledge", r, a.token(), 200);
    String id = topic.get("id").asText();
    ok("GET", "/api/v1/support/topics/" + id, null, null, 404);
    topic = ok("POST", OPS + "/knowledge/" + id + "/publish", command(topic), a.token(), 200);
    ok("GET", "/api/v1/support/topics/" + id, null, null, 200);
    r.put("requestId", UUID.randomUUID());
    r.put("version", topic.get("version").asLong());
    r.put("body", "A new draft must be reviewed again before public readers can see it.");
    topic = ok("PUT", OPS + "/knowledge/" + id, r, a.token(), 200);
    ok("GET", "/api/v1/support/topics/" + id, null, null, 404);
    ok("POST", OPS + "/knowledge/" + id + "/archive", command(topic), a.token(), 200);
  }

  @Test
  void sourcePickerUsesPublicVisibilityAndApprovedReviewsReopenOnNewReports() throws Exception {
    var f = listing();
    var a = user(true);
    var reporter = user();
    String id = f.listing();
    var sources = ok("GET", OPS + "/listing-sources?q=Reviewable", null, a.token(), 200);
    assertThat(sources.toString()).contains(id);
    assertThat(sources.get(0).has("version")).isTrue();
    ok("GET", OPS + "/listing-sources", null, reporter.token(), 403);
    var c = createCase(reporter, "LISTING_REPORT", UUID.fromString(id));
    var review = ok("GET", OPS + "/listing-reviews/" + id, null, a.token(), 200);
    review = ok("POST", OPS + "/listing-reviews/" + id + "/claim", command(review), a.token(), 200);
    var approval = command(review);
    approval.put("sourceVersion", 1);
    ok("POST", OPS + "/listing-reviews/" + id + "/approve", approval, a.token(), 200);
    createCase(reporter, "LISTING_REPORT", UUID.fromString(id));
    review = ok("GET", OPS + "/listing-reviews/" + id, null, a.token(), 200);
    assertThat(review.get("status").asText()).isEqualTo("REVIEW_REQUESTED");
    review = ok("POST", OPS + "/listing-reviews/" + id + "/claim", command(review), a.token(), 200);
    var suspension = command(review);
    suspension.put("sourceVersion", 1);
    ok("POST", OPS + "/listing-reviews/" + id + "/suspend", suspension, a.token(), 200);
    assertThat(ok("GET", OPS + "/listing-sources?q=Reviewable", null, a.token(), 200).toString())
        .doesNotContain(id);
  }

  @Test
  void withdrawnPublicationsCannotBeApprovedOrRefreshedAsCurrentSnapshots() throws Exception {
    var f = listing();
    var a = user(true);
    String id = f.listing();
    var review =
        ok(
            "POST",
            OPS + "/listing-reviews",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "listingId",
                id,
                "listingVersion",
                1,
                "reason",
                "Review this current public rental before any decision.",
                "password",
                PASSWORD),
            a.token(),
            200);
    review = ok("POST", OPS + "/listing-reviews/" + id + "/claim", command(review), a.token(), 200);
    jdbc.update(
        "UPDATE rental_listings SET status='DRAFT',version=version+1 WHERE id=?",
        UUID.fromString(id));
    ok("POST", OPS + "/listing-reviews/" + id + "/refresh", command(review), a.token(), 409);
    var approval = command(review);
    approval.put("sourceVersion", 2);
    ok("POST", OPS + "/listing-reviews/" + id + "/approve", approval, a.token(), 409);
    ok(
        "POST",
        OPS + "/account-actions",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "userId",
            UUID.randomUUID(),
            "version",
            0,
            "action",
            "RESTRICT",
            "reason",
            "An absent account must return a controlled missing response.",
            "password",
            PASSWORD),
        a.token(),
        404);
  }
}
