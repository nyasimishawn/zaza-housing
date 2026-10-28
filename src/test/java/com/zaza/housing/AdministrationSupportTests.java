package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AdministrationSupportTests extends AdministrationTestSupport {
  @Test
  void casesArePrivateUntilAnIndependentOperatorClaimsAndInternalNotesStayPrivate()
      throws Exception {
    var reporter = user();
    var outsider = user();
    var operator = user(true);
    var other = user(true);
    var c = createCase(reporter, "DISPUTE", null);
    String id = c.get("id").asText();
    ok("GET", CASES + "/" + id, null, outsider.token(), 404);
    assertThat(adminCase(operator, id).get("redacted").asBoolean()).isTrue();
    ok("GET", OPS + "/support-cases/" + id + "/messages", null, operator.token(), 403);
    c = caseCommand(operator, c, "claim");
    assertThat(c.get("redacted").asBoolean()).isFalse();
    c =
        ok(
            "POST",
            OPS + "/support-cases/" + id + "/messages",
            message(c, "Internal investigation note kept away from the reporter.", true),
            operator.token(),
            200);
    assertThat(ok("GET", CASES + "/" + id + "/messages", null, reporter.token(), 200).toString())
        .doesNotContain("Internal investigation");
    ok("GET", OPS + "/support-cases/" + id + "/messages", null, other.token(), 403);
    var question = command(c);
    question.put("resolution", "Please supply the relevant dates and evidence for this case.");
    c = ok("POST", OPS + "/support-cases/" + id + "/wait", question, operator.token(), 200);
    assertThat(c.get("status").asText()).isEqualTo("WAITING_CUSTOMER");
    c =
        ok(
            "POST",
            CASES + "/" + id + "/messages",
            message(c, "Here are the requested dates and relevant contextual details.", false),
            reporter.token(),
            200);
    assertThat(c.get("status").asText()).isEqualTo("IN_PROGRESS");
    var resolve = command(c);
    resolve.put(
        "resolution",
        "The operational evidence was reviewed and the agreed next steps were recorded.");
    c = ok("POST", OPS + "/support-cases/" + id + "/resolve", resolve, operator.token(), 200);
    c =
        ok(
            "POST",
            CASES + "/" + id + "/reopen",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                c.get("version").asLong(),
                "message",
                "Additional evidence needs to be reviewed before closure."),
            reporter.token(),
            200);
    resolve = command(c);
    resolve.put(
        "resolution",
        "The additional evidence was considered and the final response is now recorded.");
    c = ok("POST", OPS + "/support-cases/" + id + "/resolve", resolve, operator.token(), 200);
    c =
        ok(
            "POST",
            CASES + "/" + id + "/close",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "version",
                c.get("version").asLong(),
                "message",
                "I accept this recorded operational support resolution."),
            reporter.token(),
            200);
    assertThat(c.get("status").asText()).isEqualTo("CLOSED");
    ok(
        "POST",
        CASES + "/" + id + "/messages",
        message(c, "This closed case must retain its original evidence.", false),
        reporter.token(),
        409);
  }

  @Test
  void encryptedEvidenceIsScopedImmutableAndAnExactUploadRetryDoesNotDuplicate() throws Exception {
    var reporter = user();
    var outsider = user();
    var operator = user(true);
    var c = createCase(reporter, "SUPPORT", null);
    String id = c.get("id").asText();
    UUID request = UUID.randomUUID();
    long version = c.get("version").asLong();
    var response =
        upload(
            CASES + "/" + id + "/evidence",
            reporter.token(),
            request,
            version,
            "Supporting issue evidence",
            false);
    assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
    c = json.readTree(response.body());
    assertThat(
            upload(
                    CASES + "/" + id + "/evidence",
                    reporter.token(),
                    request,
                    version,
                    "Supporting issue evidence",
                    false)
                .statusCode())
        .isEqualTo(200);
    var images = ok("GET", CASES + "/" + id + "/evidence", null, reporter.token(), 200);
    assertThat(images.size()).isEqualTo(1);
    String url = images.get(0).get("contentUrl").asText();
    ok("GET", url, null, outsider.token(), 404);
    var row =
        jdbc.queryForMap("SELECT * FROM support_evidence WHERE case_id=?", UUID.fromString(id));
    byte[] cipher =
        java.nio.file.Files.readAllBytes(
            FILES.resolve("evidence").resolve((String) row.get("storage_key")));
    assertThat(java.util.Arrays.copyOf(cipher, 4))
        .isNotEqualTo(new byte[] {(byte) 137, 80, 78, 71});
    c = caseCommand(operator, c, "claim");
    var internal =
        upload(
            OPS + "/support-cases/" + id + "/evidence",
            operator.token(),
            UUID.randomUUID(),
            c.get("version").asLong(),
            "Private operator evidence",
            true);
    assertThat(internal.statusCode()).isEqualTo(200);
    assertThat(ok("GET", CASES + "/" + id + "/evidence", null, reporter.token(), 200).size())
        .isEqualTo(1);
    assertThat(
            ok("GET", OPS + "/support-cases/" + id + "/evidence", null, operator.token(), 200)
                .size())
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE support_evidence SET caption='changed' WHERE case_id=?",
                    UUID.fromString(id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void ownCasesAndPropertyRelatedListingReportsRequireAnotherOperator() throws Exception {
    var operator = user(true);
    var own = createCase(operator, "SUPPORT", null);
    ok(
        "POST",
        OPS + "/support-cases/" + own.get("id").asText() + "/claim",
        command(own),
        operator.token(),
        403);
    var f = listing();
    var reporter = user();
    var c = createCase(reporter, "LISTING_REPORT", UUID.fromString(f.listing()));
    ok(
        "POST",
        "/api/v1/organizations/" + f.org() + "/members",
        Map.of("email", operator.email(), "role", "MEMBER"),
        f.owner().token(),
        201);
    ok(
        "POST",
        OPS + "/support-cases/" + c.get("id").asText() + "/claim",
        command(c),
        operator.token(),
        403);
  }

  @Test
  void escalationHandoffAndFormerAssigneeAccessAreAudited() throws Exception {
    var reporter = user();
    var first = user(true);
    var second = user(true);
    var c = createCase(reporter, "DISPUTE", null);
    String id = c.get("id").asText();
    c = caseCommand(first, c, "claim");
    c = caseCommand(first, c, "escalate");
    assertThat(c.get("priority").asText()).isEqualTo("URGENT");
    var transfer = command(c);
    transfer.put("assigneeId", second.id());
    c = ok("POST", OPS + "/support-cases/" + id + "/assign", transfer, first.token(), 200);
    assertThat(c.get("redacted").asBoolean()).isTrue();
    ok("GET", OPS + "/support-cases/" + id + "/messages", null, first.token(), 403);
    assertThat(adminCase(second, id).get("redacted").asBoolean()).isFalse();
    assertThat(
            ok("GET", OPS + "/support-cases/" + id + "/history", null, second.token(), 200).size())
        .isGreaterThan(2);
  }

  @Test
  void replayCannotChangeScopeAndMessagesRejectStaleVersions() throws Exception {
    var reporter = user();
    var c = createCase(reporter, "SUPPORT", null);
    String id = c.get("id").asText();
    var r = message(c, "An exact message request must have one immutable history entry.", false);
    var updated = ok("POST", CASES + "/" + id + "/messages", r, reporter.token(), 200);
    ok("POST", CASES + "/" + id + "/messages", r, reporter.token(), 200);
    assertThat(ok("GET", CASES + "/" + id + "/messages", null, reporter.token(), 200).size())
        .isEqualTo(1);
    var changed = new HashMap<String, Object>(r);
    changed.put("body", "Changed content may not reuse the same request identifier.");
    ok("POST", CASES + "/" + id + "/messages", changed, reporter.token(), 409);
    ok(
        "POST",
        CASES + "/" + id + "/messages",
        message(c, "A different request still needs the current version.", false),
        reporter.token(),
        409);
    ok(
        "POST",
        CASES + "/" + id + "/messages",
        message(updated, "Reporters cannot create private operator investigation notes.", true),
        reporter.token(),
        403);
  }

  @Test
  void notificationsAreScopedAndClosedCaseDatabaseHistoryCannotBeErased() throws Exception {
    var reporter = user();
    var outsider = user();
    var c = createCase(reporter, "SUPPORT", null);
    var notifications = ok("GET", "/api/v1/me/support/notifications", null, reporter.token(), 200);
    assertThat(notifications.size()).isGreaterThan(0);
    String id = notifications.get(0).get("id").asText();
    ok("POST", "/api/v1/me/support/notifications/" + id + "/read", Map.of(), outsider.token(), 200);
    assertThat(
            ok("GET", "/api/v1/me/support/notifications", null, reporter.token(), 200)
                .get(0)
                .get("readAt")
                .isNull())
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM support_cases WHERE id=?", UUID.fromString(c.get("id").asText())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void revokedOperatorsCanBeReplacedAndOperatorNotificationsUseAssignedRoute() throws Exception {
    var reporter = user();
    var first = user(true);
    var next = user(true);
    var c = createCase(reporter, "SUPPORT", null);
    String id = c.get("id").asText();
    c = caseCommand(first, c, "claim");
    c =
        ok(
            "POST",
            CASES + "/" + id + "/messages",
            message(c, "Please check this new relevant operational information.", false),
            reporter.token(),
            200);
    var notices = ok("GET", "/api/v1/me/support/notifications", null, first.token(), 200);
    assertThat(notices.get(0).get("adminView").asBoolean()).isTrue();
    assertThat(
            ok("GET", "/api/v1/me/support/notifications", null, reporter.token(), 200)
                .get(0)
                .get("adminView")
                .asBoolean())
        .isFalse();
    jdbc.update("UPDATE app_users SET active=false WHERE id=?", first.id());
    c = caseCommand(next, c, "claim");
    assertThat(c.get("assignedTo").asText()).isEqualTo(next.id().toString());
    assertThat(c.get("redacted").asBoolean()).isFalse();
    ok("GET", OPS + "/support-cases/" + id + "/messages", null, first.token(), 401);
  }

  @Test
  void staleEvidenceUploadDoesNotPersistFilesAndInternalContentIsNotDownloadableByReporter()
      throws Exception {
    var reporter = user();
    var a = user(true);
    var c = createCase(reporter, "SUPPORT", null);
    String id = c.get("id").asText();
    long before = jdbc.queryForObject("SELECT count(*) FROM support_evidence", Long.class);
    assertThat(
            upload(
                    CASES + "/" + id + "/evidence",
                    reporter.token(),
                    UUID.randomUUID(),
                    99,
                    "Stale upload attempt",
                    false)
                .statusCode())
        .isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM support_evidence", Long.class))
        .isEqualTo(before);
    c = caseCommand(a, c, "claim");
    assertThat(
            upload(
                    OPS + "/support-cases/" + id + "/evidence",
                    a.token(),
                    UUID.randomUUID(),
                    c.get("version").asLong(),
                    "Internal review evidence",
                    true)
                .statusCode())
        .isEqualTo(200);
    var images = ok("GET", OPS + "/support-cases/" + id + "/evidence", null, a.token(), 200);
    String image = images.get(0).get("id").asText();
    ok("GET", CASES + "/" + id + "/evidence/" + image + "/content", null, reporter.token(), 404);
  }
}
