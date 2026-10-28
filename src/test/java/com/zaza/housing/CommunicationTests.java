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
class CommunicationTests {
  private static final String PASSWORD = "Lease-test-password-42";
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired CommunicationTestClock clock;
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
  void contactProjectionAndEnquiryNeverExposePrivatePremisesOrRequireATenantProfile()
      throws Exception {
    var i = advertised();
    Account customer = account();
    var projection = node(call("GET", contact(i), null, customer, 200));
    assertThat(projection.get("listingVersion").asLong()).isEqualTo(1);
    assertThat(projection.toString())
        .doesNotContain("Private lease premises", "ownerOrganizationId", "unitId");
    call("GET", contact(i), null, null, 401);
    var b = enquiryBody(i, customer);
    call("POST", enquiryPath(i), b, null, 401);
    var c = connect(i, customer, b);
    assertThat(current(c, c.customer).toString())
        .doesNotContain(
            "tenantId", "propertyId", "unitId", "email", "phone", "Private lease premises");
    assertThat(messages(c, c.i.owner).get(0).get("senderRole").asText()).isEqualTo("CUSTOMER");
  }

  @Test
  void unavailableAndStaleListingsCannotCreateNewEnquiries() throws Exception {
    var i = advertised();
    var b = enquiryBody(i, i.tenant);
    b.put("listingVersion", 0);
    call("POST", enquiryPath(i), b, i.tenant, 409);
    call(
        "POST",
        i.propertyPath() + "/listings/" + i.listing + "/withdraw",
        Map.of("version", 1),
        i.owner,
        200);
    b.put("listingVersion", 1);
    call("POST", enquiryPath(i), b, i.tenant, 404);
  }

  @Test
  void enquiryRetriesReturnTheSameThreadAndCannotImpersonateAnotherActor() throws Exception {
    var i = advertised();
    var b = enquiryBody(i, i.tenant);
    var c = connect(i, i.tenant, b);
    assertThat(node(call("POST", enquiryPath(i), b, i.tenant, 200)).get("id").asText())
        .isEqualTo(c.id);
    call("POST", enquiryPath(i), b, account(), 409);
    b.put("message", "Changed enquiry text");
    call("POST", enquiryPath(i), b, i.tenant, 409);
    call("POST", enquiryPath(i), enquiryBody(i, i.tenant), i.tenant, 409);
    call("POST", enquiryPath(i), enquiryBody(i, i.owner), i.owner, 403);
    assertThat(messages(c, c.customer).size()).isEqualTo(1);
  }

  @Test
  void hourlyEnquiryLimitIsScopedToTheProspectAcrossProperties() throws Exception {
    Account customer = account();
    var one = advertised();
    var two = advertised();
    var three = advertised();
    connect(one, customer, enquiryBody(one, customer));
    connect(two, customer, enquiryBody(two, customer));
    call("POST", enquiryPath(three), enquiryBody(three, customer), customer, 429);
  }

  @Test
  void messagesUseStableSequencesAndRoleLabelsWithoutDisclosingAccounts() throws Exception {
    var c = chat();
    send(c, c.i.owner, "A private response.");
    send(c, c.customer, "Thanks for the response.");
    var rows = messages(c, c.customer);
    assertThat(rows.size()).isEqualTo(3);
    assertThat(rows.get(2).get("sequence").asLong()).isEqualTo(3);
    assertThat(rows.get(1).get("senderRole").asText()).isEqualTo("MANAGER");
    assertThat(rows.get(0).get("mine").asBoolean()).isTrue();
    assertThat(rows.toString()).doesNotContain("authorId", "email", "phone", "passwordHash");
    assertThat(
            node(call("GET", c.base() + "/messages?after=2&limit=1", null, c.customer, 200)).size())
        .isEqualTo(1);
  }

  @Test
  void unreadCursorsArePersonalMonotonicAndCannotHideFutureMessages() throws Exception {
    var c = chat();
    assertThat(current(c, c.i.owner).get("unreadMessages").asLong()).isEqualTo(1);
    assertThat(current(c, c.customer).get("unreadMessages").asLong()).isZero();
    send(c, c.i.owner, "Owner response.");
    call(
        "POST",
        c.base() + "/read",
        Map.of("requestId", UUID.randomUUID(), "throughSequence", 3),
        c.customer,
        400);
    call(
        "POST",
        c.base() + "/read",
        Map.of("requestId", UUID.randomUUID(), "throughSequence", 2),
        c.customer,
        200);
    call(
        "POST",
        c.base() + "/read",
        Map.of("requestId", UUID.randomUUID(), "throughSequence", 1),
        c.customer,
        200);
    assertThat(current(c, c.customer).get("readThrough").asLong()).isEqualTo(2);
    assertThat(current(c, c.customer).get("unreadMessages").asLong()).isZero();
    assertThat(current(c, c.i.owner).get("unreadMessages").asLong()).isEqualTo(1);
  }

  @Test
  void mutingOnlyChangesYourOwnNotificationsAndDoesNotPublishMessageBodies() throws Exception {
    var c = chat();
    call(
        "POST",
        c.base() + "/preferences",
        Map.of("requestId", UUID.randomUUID(), "muted", true),
        c.customer,
        200);
    long before = inbox(c.customer).size();
    send(c, c.i.owner, "DO_NOT_INCLUDE_THIS_TEXT_IN_AN_INBOX_PREVIEW");
    assertThat(inbox(c.customer).size()).isEqualTo(before);
    assertThat(current(c, c.customer).get("muted").asBoolean()).isTrue();
    assertThat(current(c, c.i.owner).get("muted").asBoolean()).isFalse();
    assertThat(current(c, c.customer).get("unreadMessages").asLong()).isEqualTo(1);
    call(
        "POST",
        c.base() + "/preferences",
        Map.of("requestId", UUID.randomUUID(), "muted", false),
        c.customer,
        200);
    send(c, c.i.owner, "SECOND_SECRET_BODY");
    assertThat(inbox(c.customer).toString())
        .contains(c.id)
        .doesNotContain("SECOND_SECRET_BODY", "DO_NOT_INCLUDE_THIS_TEXT");
  }

  @Test
  void closingRejectsStaleVersionsAndReopeningDoesNotEraseMessages() throws Exception {
    var c = chat();
    var stale = conversationCommand(c, c.customer);
    send(c, c.i.owner, "A new response.");
    call("POST", c.base() + "/close", stale, c.customer, 409);
    var b = conversationCommand(c, c.customer);
    call("POST", c.base() + "/close", b, c.customer, 200);
    call("POST", c.base() + "/close", b, c.customer, 200);
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "Attempt while closed"),
        c.i.owner,
        409);
    call("POST", c.base() + "/reopen", conversationCommand(c, c.customer), c.customer, 200);
    assertThat(messages(c, c.customer).size()).isEqualTo(2);
  }

  @Test
  void concurrentMessagesAppendExactlyOnceWithoutRequiringAStaleInboxVersion() throws Exception {
    var c = chat();
    var b = Map.of("requestId", UUID.randomUUID(), "body", "One message retried concurrently.");
    var one =
        client.sendAsync(
            request("POST", c.base() + "/messages", b, c.customer),
            HttpResponse.BodyHandlers.ofString());
    var two =
        client.sendAsync(
            request("POST", c.base() + "/messages", b, c.customer),
            HttpResponse.BodyHandlers.ofString());
    assertThat(one.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(two.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(messages(c, c.customer).size()).isEqualTo(2);
    var a =
        client.sendAsync(
            request(
                "POST",
                c.base() + "/messages",
                Map.of("requestId", UUID.randomUUID(), "body", "A"),
                c.i.owner),
            HttpResponse.BodyHandlers.ofString());
    var d =
        client.sendAsync(
            request(
                "POST",
                c.base() + "/messages",
                Map.of("requestId", UUID.randomUUID(), "body", "B"),
                c.i.owner),
            HttpResponse.BodyHandlers.ofString());
    assertThat(a.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(d.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(current(c, c.customer).get("lastSequence").asLong()).isEqualTo(4);
  }

  @Test
  void authorsCanWithdrawRecentMessagesButCannotRewriteTheEvidence() throws Exception {
    var c = chat();
    String first = messages(c, c.customer).get(0).get("id").asText();
    call("POST", c.base() + "/messages/" + first + "/redact", redaction(), c.i.owner, 403);
    call("POST", c.base() + "/messages/" + first + "/redact", redaction(), c.customer, 200);
    assertThat(messages(c, c.customer).get(0).get("body").isNull()).isTrue();
    assertThat(messages(c, c.customer).get(0).get("redacted").asBoolean()).isTrue();
    send(c, c.customer, "Retained original evidence.");
    String second = messages(c, c.customer).get(1).get("id").asText();
    clock.now = clock.instant().plusSeconds(901);
    call("POST", c.base() + "/messages/" + second + "/redact", redaction(), c.customer, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM conversation_messages WHERE id=?",
                String.class,
                UUID.fromString(first)))
        .isEqualTo("Interested in a viewing of this listing.");
  }

  @Test
  void reportQueuesRevealNoPrivateMessagesOrReasonsBeforeAssignment() throws Exception {
    var c = chat();
    String msg = send(c, c.i.owner, "SENSITIVE_REPORTED_BODY");
    String report = report(c, msg);
    Account admin = operator();
    var queue =
        node(call("GET", "/api/v1/admin/conversation-reports?status=OPEN", null, admin, 200));
    assertThat(queue.toString())
        .contains(report)
        .doesNotContain("SENSITIVE_REPORTED_BODY", "PRIVATE_REPORT_REASON", c.id, msg);
    call("GET", c.base() + "/messages", null, admin, 404);
    call("GET", reportAdmin(report) + "/evidence", null, admin, 404);
    call("GET", "/api/v1/me/conversation-reports/" + report, null, c.i.owner, 404);
  }

  @Test
  void claimingAReportRequiresIndependentPasswordConfirmedModeration() throws Exception {
    var c = chat();
    String msg = send(c, c.i.owner, "Reported content.");
    String report = report(c, msg);
    Account admin = operator();
    var wrong = review(0);
    wrong.put("password", "Wrong-password-confirmation");
    call("POST", reportAdmin(report) + "/claim", wrong, admin, 401);
    promote(c.i.owner);
    call("POST", reportAdmin(report) + "/claim", review(0), c.i.owner, 403);
    claim(report, admin);
    send(c, c.i.owner, "UNRELATED_PRIVATE_MESSAGE");
    var evidence = node(call("GET", reportAdmin(report) + "/evidence", null, admin, 200));
    assertThat(evidence.toString())
        .contains("Reported content.")
        .doesNotContain("UNRELATED_PRIVATE_MESSAGE");
    call("GET", c.base() + "/messages", null, admin, 404);
  }

  @Test
  void moderationWithholdsReportedTextButRetainsTheOriginalForTheAssignedCase() throws Exception {
    var c = chat();
    String msg = send(c, c.i.owner, "ORIGINAL_REPORTED_BODY");
    String report = report(c, msg);
    Account admin = operator();
    claim(report, admin);
    var r = review(1);
    r.put("resolution", "REDACT");
    call("POST", reportAdmin(report) + "/resolve", r, admin, 200);
    call("POST", reportAdmin(report) + "/resolve", r, admin, 200);
    assertThat(messages(c, c.customer).get(1).get("body").isNull()).isTrue();
    assertThat(node(call("GET", reportAdmin(report) + "/evidence", null, admin, 200)).toString())
        .contains("ORIGINAL_REPORTED_BODY");
    assertThat(node(call("GET", c.base() + "/history", null, c.i.owner, 200)).toString())
        .contains("MESSAGE_MODERATED")
        .doesNotContain(report, "PRIVATE_REPORT_REASON");
  }

  @Test
  void releasingAModerationCaseRevokesEvidenceUntilANewOperatorClaimsIt() throws Exception {
    var c = chat();
    String report = report(c, send(c, c.i.owner, "Reported body."));
    Account first = operator(), second = operator();
    claim(report, first);
    var release = review(1);
    call("POST", reportAdmin(report) + "/release", release, first, 200);
    call("POST", reportAdmin(report) + "/release", release, first, 200);
    call("GET", reportAdmin(report) + "/evidence", null, first, 404);
    claim(report, second, 2);
    call("GET", reportAdmin(report) + "/evidence", null, second, 200);
    call("GET", reportAdmin(report) + "/evidence", null, first, 404);
  }

  @Test
  void moderatorsWhoBecomeBusinessParticipantsCannotContinueReadingOrDecidingACase()
      throws Exception {
    var c = chat();
    String report = report(c, send(c, c.i.owner, "Reported body."));
    Account admin = operator();
    claim(report, admin);
    call(
        "POST",
        "/api/v1/organizations/" + c.i.organization + "/members",
        Map.of("email", admin.email, "role", "ADMIN"),
        c.i.owner,
        201);
    call("GET", reportAdmin(report) + "/evidence", null, admin, 403);
    var r = review(1);
    r.put("resolution", "REDACT");
    call("POST", reportAdmin(report) + "/resolve", r, admin, 403);
    call("POST", reportAdmin(report) + "/release", review(1), admin, 200);
  }

  @Test
  void usersCannotReportTheirOwnMessageAndReportersKeepOnlyTheirOwnCaseStatus() throws Exception {
    var c = chat();
    String own = messages(c, c.customer).get(0).get("id").asText();
    call("POST", c.base() + "/messages/" + own + "/reports", reportBody(), c.customer, 400);
    String msg = send(c, c.i.owner, "Reported text.");
    String report = report(c, msg);
    var rows = node(call("GET", "/api/v1/me/conversation-reports", null, c.customer, 200));
    assertThat(rows.toString()).contains(report);
    call("GET", "/api/v1/me/conversation-reports/" + report, null, account(), 404);
  }

  @Test
  void ordinaryOrganizationMembersAndStrangersCannotReadPrivateInboxes() throws Exception {
    var c = chat();
    Account member = account(), other = account();
    call(
        "POST",
        "/api/v1/organizations/" + c.i.organization + "/members",
        Map.of("email", member.email, "role", "MEMBER"),
        c.i.owner,
        201);
    call("GET", c.base(), null, member, 404);
    call("GET", c.base() + "/messages", null, other, 404);
    call("GET", "/api/v1/organizations/" + c.i.organization + "/conversations", null, member, 403);
    call("GET", c.base(), null, null, 401);
  }

  @Test
  void leaseConversationsRequireAnExecutedLeaseAndShareOnlyItsOwnBusinessContext()
      throws Exception {
    var i = inventory();
    var lease = sent(i);
    call("POST", own(lease) + "/conversations", open("SHARED"), i.tenant, 409);
    lease = sign(i, lease, true);
    lease = sign(i, lease, false);
    String id = open(i.tenant, own(lease), "SHARED");
    assertThat(open(i.owner, i.path(lease), "SHARED")).isEqualTo(id);
    call("GET", "/api/v1/conversations/" + id, null, account(), 404);
    call("POST", own(lease) + "/conversations", open("PROCUREMENT"), i.tenant, 400);
  }

  @Test
  void maintenanceProcurementMessagesExcludeOccupantsAndProviderCannotCloseTheThread()
      throws Exception {
    var s = accepted();
    String shared = open(s.i.tenant, s.tenant(), "SHARED"),
        privateId = open(s.i.owner, s.manager(), "PROCUREMENT");
    assertThat(open(s.p.actor, s.job(), "PROCUREMENT")).isEqualTo(privateId);
    call("GET", "/api/v1/conversations/" + privateId, null, s.i.tenant, 404);
    call("POST", s.tenant() + "/conversations", open("PROCUREMENT"), s.i.tenant, 403);
    call(
        "POST",
        "/api/v1/conversations/" + shared + "/messages",
        Map.of(
            "requestId", UUID.randomUUID(), "body", "Visit instructions provided by the occupant."),
        s.i.tenant,
        200);
    var current = node(call("GET", "/api/v1/conversations/" + shared, null, s.p.actor, 200));
    assertThat(current.toString()).doesNotContain("leaseId", "tenantId", "propertyId", "unitId");
    call(
        "POST",
        "/api/v1/conversations/" + shared + "/close",
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            current.get("version").asLong(),
            "reason",
            "Provider requested closure."),
        s.p.actor,
        403);
  }

  @Test
  void privateServiceBookingConversationsStayHiddenFromLandlords() throws Exception {
    var s = booking(provider());
    call("POST", s.job() + "/accept", cmd(s), s.p.actor, 200);
    String id = open(s.i.tenant, s.tenant(), "SHARED");
    assertThat(open(s.p.actor, s.job(), "SHARED")).isEqualTo(id);
    call("GET", "/api/v1/conversations/" + id, null, s.i.owner, 404);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + s.i.organization + "/conversations",
                    null,
                    s.i.owner,
                    200))
                .toString())
        .doesNotContain(id);
  }

  @Test
  void changedAgencyDelegationImmediatelyChangesConversationAccess() throws Exception {
    var c = chat();
    Account agency = account();
    String org = organization(agency, "AGENCY");
    call(
        "PATCH",
        c.i.propertyPath() + "/management",
        Map.of("version", 1, "managerOrganizationId", org),
        c.i.owner,
        200);
    call("GET", c.base(), null, agency, 200);
    send(c, agency, "Agency response as the current manager.");
    var revoke = new HashMap<String, Object>();
    revoke.put("version", 2);
    revoke.put("managerOrganizationId", null);
    call("PATCH", c.i.propertyPath() + "/management", revoke, c.i.owner, 200);
    call("GET", c.base() + "/messages", null, agency, 404);
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "Attempt after revocation"),
        agency,
        404);
    assertThat(
            node(call("GET", "/api/v1/organizations/" + org + "/conversations", null, agency, 200))
                .toString())
        .doesNotContain(c.id);
  }

  @Test
  void providerReassignmentNeverExposesOldAssignmentConversationsToTheReplacement()
      throws Exception {
    var scenario = accepted();
    String old = open(scenario.p.actor, scenario.job(), "SHARED");
    Account previous = scenario.p.actor;
    call(
        "POST",
        "/api/v1/conversations/" + old + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "PREVIOUS_PROVIDER_DISCUSSION"),
        previous,
        200);
    call("POST", scenario.job() + "/release", cmd(scenario), previous, 200);
    Provider replacement = provider();
    assign(scenario, replacement);
    call("POST", scenario.job() + "/accept", cmd(scenario), replacement.actor, 200);
    String fresh = open(replacement.actor, scenario.job(), "SHARED");
    assertThat(fresh).isNotEqualTo(old);
    call("GET", "/api/v1/conversations/" + old, null, previous, 404);
    call("GET", "/api/v1/conversations/" + old, null, replacement.actor, 404);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/conversations/" + fresh + "/messages",
                    null,
                    replacement.actor,
                    200))
                .toString())
        .doesNotContain("PREVIOUS_PROVIDER_DISCUSSION");
  }

  @Test
  void providersCannotReadOrCreateCustomerThreadsUntilTheyAcceptTheJob() throws Exception {
    var scenario = assigned();
    String id = open(scenario.i.tenant, scenario.tenant(), "SHARED");
    call("POST", scenario.job() + "/conversations", open("SHARED"), scenario.p.actor, 409);
    call("GET", "/api/v1/conversations/" + id, null, scenario.p.actor, 404);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + scenario.p.org + "/conversations",
                    null,
                    scenario.p.actor,
                    200))
                .toString())
        .doesNotContain(id);
    call("POST", scenario.job() + "/accept", cmd(scenario), scenario.p.actor, 200);
    call("GET", "/api/v1/conversations/" + id, null, scenario.p.actor, 200);
  }

  @Test
  void viewingProposalsNeedConfirmationFromTheOtherBusinessParty() throws Exception {
    var c = chat();
    var v =
        proposeViewing(
            c, c.customer, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400));
    String id = v.get("id").asText();
    assertThat(v.get("status").asText()).isEqualTo("PENDING");
    call("POST", viewingPath(c) + "/" + id + "/confirm", viewingCommand(c, id), c.customer, 403);
    var confirmed = confirmViewing(c, id, c.i.owner);
    assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(confirmed.get("confirmedSlot").get("meetingInstructions").asText())
        .contains("publicly agreed entrance");
    assertThat(confirmed.toString()).doesNotContain("Private lease premises");
  }

  @Test
  void viewingTimeBoundsAndPublicationVersionAreValidatedBeforeScheduling() throws Exception {
    var c = chat();
    var b = proposal(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(3900));
    call("POST", viewingPath(c), b, c.customer, 400);
    b = proposal(c, clock.instant().minusSeconds(60), clock.instant().plusSeconds(1800));
    call("POST", viewingPath(c), b, c.customer, 400);
    b =
        proposal(
            c,
            clock.instant().plus(Duration.ofDays(91)),
            clock.instant().plus(Duration.ofDays(91)).plusSeconds(1800));
    call("POST", viewingPath(c), b, c.customer, 400);
    b = proposal(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400));
    b.put("listingVersion", 0);
    call("POST", viewingPath(c), b, c.customer, 409);
  }

  @Test
  void overlappingConfirmedAppointmentsForOneUnitAreRejected() throws Exception {
    var one = chat();
    Account other = account();
    var two = connect(one.i, other, enquiryBody(one.i, other));
    Instant start = clock.instant().plusSeconds(3600), end = start.plusSeconds(1800);
    String a = proposeViewing(one, one.customer, start, end).get("id").asText(),
        b =
            proposeViewing(two, two.customer, start.plusSeconds(900), end.plusSeconds(900))
                .get("id")
                .asText();
    confirmViewing(one, a, one.i.owner);
    call("POST", viewingPath(two) + "/" + b + "/confirm", viewingCommand(two, b), two.i.owner, 409);
  }

  @Test
  void simultaneousConfirmationsAcrossPropertiesCannotDoubleBookTheVisitor() throws Exception {
    var one = chat();
    var other = advertised();
    var two = connect(other, one.customer, enquiryBody(other, one.customer));
    Instant start = clock.instant().plusSeconds(3600), end = start.plusSeconds(1800);
    String a = proposeViewing(one, one.customer, start, end).get("id").asText(),
        b = proposeViewing(two, two.customer, start, end).get("id").asText();
    var first =
        client.sendAsync(
            request(
                "POST",
                viewingPath(one) + "/" + a + "/confirm",
                viewingCommand(one, a),
                one.i.owner),
            HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            request(
                "POST",
                viewingPath(two) + "/" + b + "/confirm",
                viewingCommand(two, b),
                two.i.owner),
            HttpResponse.BodyHandlers.ofString());
    assertThat(
            List.of(
                first.get(30, TimeUnit.SECONDS).statusCode(),
                second.get(30, TimeUnit.SECONDS).statusCode()))
        .containsExactlyInAnyOrder(200, 409);
  }

  @Test
  void adjacentViewingSlotsDoNotOverlap() throws Exception {
    var one = chat();
    Account customer = account();
    var two = connect(one.i, customer, enquiryBody(one.i, customer));
    Instant start = clock.instant().plusSeconds(3600), end = start.plusSeconds(1800);
    String a = proposeViewing(one, one.customer, start, end).get("id").asText(),
        b = proposeViewing(two, two.customer, end, end.plusSeconds(1800)).get("id").asText();
    confirmViewing(one, a, one.i.owner);
    confirmViewing(two, b, two.i.owner);
  }

  @Test
  void reschedulingKeepsTheOriginalSlotReservedUntilANewProposalIsAccepted() throws Exception {
    var one = chat();
    Account customer = account();
    var two = connect(one.i, customer, enquiryBody(one.i, customer));
    Instant start = clock.instant().plusSeconds(3600), end = start.plusSeconds(1800);
    String a = proposeViewing(one, one.customer, start, end).get("id").asText();
    confirmViewing(one, a, one.i.owner);
    var change = proposal(one, start.plusSeconds(7200), end.plusSeconds(7200));
    change.put("version", viewing(one, a).get("version").asLong());
    call("POST", viewingPath(one) + "/" + a + "/reschedule", change, one.i.owner, 200);
    assertThat(viewing(one, a).get("status").asText()).isEqualTo("RESCHEDULE_PENDING");
    String b = proposeViewing(two, two.customer, start, end).get("id").asText();
    call("POST", viewingPath(two) + "/" + b + "/confirm", viewingCommand(two, b), two.i.owner, 409);
    confirmViewing(one, a, one.customer);
    confirmViewing(two, b, two.i.owner);
  }

  @Test
  void decliningARescheduleRestoresThePreviouslyConfirmedSlot() throws Exception {
    var c = chat();
    Instant start = clock.instant().plusSeconds(3600), end = start.plusSeconds(1800);
    String id = proposeViewing(c, c.customer, start, end).get("id").asText();
    confirmViewing(c, id, c.i.owner);
    String original = viewing(c, id).get("confirmedProposalId").asText();
    var change = proposal(c, start.plusSeconds(7200), end.plusSeconds(7200));
    change.put("version", viewing(c, id).get("version").asLong());
    call("POST", viewingPath(c) + "/" + id + "/reschedule", change, c.i.owner, 200);
    call("POST", viewingPath(c) + "/" + id + "/decline", viewingCommand(c, id), c.customer, 200);
    var result = viewing(c, id);
    assertThat(result.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(result.get("currentProposalId").asText()).isEqualTo(original);
    assertThat(
            node(call("GET", viewingPath(c) + "/" + id + "/history", null, c.customer, 200)).size())
        .isEqualTo(4);
  }

  @Test
  void changedListingsBlockConfirmationButStillAllowCancellation() throws Exception {
    var c = chat();
    String id =
        proposeViewing(
                c, c.customer, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400))
            .get("id")
            .asText();
    call(
        "POST",
        c.i.propertyPath() + "/listings/" + c.i.listing + "/withdraw",
        Map.of("version", 1),
        c.i.owner,
        200);
    call("POST", viewingPath(c) + "/" + id + "/confirm", viewingCommand(c, id), c.i.owner, 404);
    call("POST", viewingPath(c) + "/" + id + "/cancel", viewingCommand(c, id), c.customer, 200);
    assertThat(viewing(c, id).get("status").asText()).isEqualTo("CANCELLED");
  }

  @Test
  void activeViewingsMustBeCancelledBeforeClosingAnEnquiry() throws Exception {
    var c = chat();
    String id =
        proposeViewing(
                c, c.i.owner, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400))
            .get("id")
            .asText();
    call("POST", c.base() + "/close", conversationCommand(c, c.customer), c.customer, 409);
    call("POST", viewingPath(c) + "/" + id + "/cancel", viewingCommand(c, id), c.customer, 200);
    call("POST", c.base() + "/close", conversationCommand(c, c.customer), c.customer, 200);
    call(
        "POST",
        viewingPath(c),
        proposal(c, clock.instant().plusSeconds(7200), clock.instant().plusSeconds(9000)),
        c.customer,
        409);
  }

  @Test
  void viewingOutcomesAreRecordedByManagersOnlyAfterTheConfirmedVisitEnds() throws Exception {
    var c = chat();
    Instant end = clock.instant().plusSeconds(5400);
    String id = proposeViewing(c, c.customer, end.minusSeconds(1800), end).get("id").asText();
    confirmViewing(c, id, c.i.owner);
    call("POST", viewingPath(c) + "/" + id + "/complete", viewingCommand(c, id), c.i.owner, 409);
    clock.now = end.plusSeconds(1);
    refresh(c);
    call("POST", viewingPath(c) + "/" + id + "/complete", viewingCommand(c, id), c.customer, 403);
    call("POST", viewingPath(c) + "/" + id + "/no-show", viewingCommand(c, id), c.i.owner, 200);
    assertThat(viewing(c, id).get("status").asText()).isEqualTo("NO_SHOW");
    call("POST", viewingPath(c) + "/" + id + "/cancel", viewingCommand(c, id), c.customer, 409);
  }

  @Test
  void propertyCalendarsArePrivateBoundedAndRespectPagination() throws Exception {
    var c = chat();
    String id =
        proposeViewing(
                c, c.customer, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400))
            .get("id")
            .asText();
    String calendar = c.i.propertyPath() + "/viewings/calendar",
        range = "?from=" + clock.instant() + "&to=" + clock.instant().plus(Duration.ofDays(2));
    assertThat(node(call("GET", calendar + range, null, c.i.owner, 200)).toString()).contains(id);
    call("GET", calendar + range, null, c.customer, 404);
    call("GET", calendar + range, null, account(), 404);
    call(
        "GET",
        calendar + "?from=" + clock.instant() + "&to=" + clock.instant().plus(Duration.ofDays(32)),
        null,
        c.i.owner,
        400);
    call("GET", "/api/v1/me/viewings?from=" + clock.instant(), null, c.customer, 400);
    call("GET", viewingPath(c) + "?limit=101", null, c.customer, 400);
  }

  @Test
  void viewingRetriesAreBoundToTheAppointmentActorAndCommandPayload() throws Exception {
    var c = chat();
    var b = proposal(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400));
    String id = node(call("POST", viewingPath(c), b, c.customer, 200)).get("id").asText();
    assertThat(node(call("POST", viewingPath(c), b, c.customer, 200)).get("id").asText())
        .isEqualTo(id);
    call("POST", viewingPath(c), b, c.i.owner, 409);
    b.put("reason", "A different reason.");
    call("POST", viewingPath(c), b, c.customer, 409);
    var confirmation = viewingCommand(c, id);
    call("POST", viewingPath(c) + "/" + id + "/confirm", confirmation, c.i.owner, 200);
    call("POST", viewingPath(c) + "/" + id + "/confirm", confirmation, c.i.owner, 200);
    call("POST", viewingPath(c) + "/" + id + "/cancel", confirmation, c.i.owner, 409);
  }

  @Test
  void messageAuditFailureRollsBackSequenceUnreadNotificationsAndIdempotency() throws Exception {
    var c = chat();
    String constraint = constraint();
    long before = inbox(c.customer).size();
    var b = Map.of("requestId", UUID.randomUUID(), "body", "Atomic message.");
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'CONVERSATION_MESSAGE_SENT') NOT VALID");
    try {
      call("POST", c.base() + "/messages", b, c.i.owner, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(messages(c, c.customer).size()).isEqualTo(1);
    assertThat(current(c, c.customer).get("lastSequence").asLong()).isEqualTo(1);
    assertThat(inbox(c.customer).size()).isEqualTo(before);
    call("POST", c.base() + "/messages", b, c.i.owner, 200);
    assertThat(messages(c, c.customer).size()).isEqualTo(2);
  }

  @Test
  void failedViewingAuditLeavesNoOrphanProposalOrReservedSlot() throws Exception {
    var c = chat();
    String constraint = constraint();
    var b = proposal(c, clock.instant().plusSeconds(3600), clock.instant().plusSeconds(5400));
    jdbc.execute(
        "ALTER TABLE audit_events ADD CONSTRAINT "
            + constraint
            + " CHECK(action<>'CONVERSATION_VIEWING_PROPOSED') NOT VALID");
    try {
      call("POST", viewingPath(c), b, c.customer, 409);
    } finally {
      jdbc.execute("ALTER TABLE audit_events DROP CONSTRAINT " + constraint);
    }
    assertThat(node(call("GET", viewingPath(c), null, c.customer, 200)).size()).isZero();
    call("POST", viewingPath(c), b, c.customer, 200);
    assertThat(node(call("GET", viewingPath(c), null, c.customer, 200)).size()).isEqualTo(1);
  }

  @Test
  void messageAndViewingEvidenceCannotBeEditedOrDeletedThroughTheDatabase() throws Exception {
    var c = chat();
    String msg = messages(c, c.customer).get(0).get("id").asText(),
        id =
            proposeViewing(
                    c,
                    c.customer,
                    clock.instant().plusSeconds(3600),
                    clock.instant().plusSeconds(5400))
                .get("currentProposalId")
                .asText();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE conversation_messages SET body='changed' WHERE id=?",
                    UUID.fromString(msg)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.update("DELETE FROM viewing_proposals WHERE id=?", UUID.fromString(id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE conversations SET customer_id=? WHERE id=?",
                    c.i.owner.id,
                    UUID.fromString(c.id)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void messageRateLimitsAndControlCharacterValidationApplyWithoutTreatingTextAsMarkup()
      throws Exception {
    var c = chat();
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "\u001b[31mInvalid control text"),
        c.customer,
        400);
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "   "),
        c.customer,
        400);
    send(c, c.customer, "<script>literal plain text</script>");
    send(c, c.customer, "Another line\nwith text");
    send(c, c.customer, "Fourth customer message including the enquiry.");
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", "Rate limited fifth message"),
        c.customer,
        429);
    assertThat(messages(c, c.customer).get(1).get("body").asText()).contains("<script>");
    clock.now = clock.instant().plusSeconds(61);
    send(c, c.customer, "A new rolling rate window permits messages.");
  }

  @Test
  void privateConversationResponsesDisableCachingAndBoundMessagePagination() throws Exception {
    var c = chat();
    assertThat(
            call("GET", c.base(), null, c.customer, 200)
                .headers()
                .firstValue("Cache-Control")
                .orElse(""))
        .contains("no-store");
    call("GET", c.base() + "/messages?after=-1", null, c.customer, 400);
    call("GET", c.base() + "/messages?limit=101", null, c.customer, 400);
    call("GET", "/api/v1/me/conversations?status=UNKNOWN", null, c.customer, 400);
    assertThat(
            node(call("GET", "/api/v1/me/conversations/summary", null, c.customer, 200))
                .get("open")
                .asLong())
        .isEqualTo(1);
    assertThat(
            node(call(
                    "GET",
                    "/api/v1/organizations/" + c.i.organization + "/conversations?unreadOnly=true",
                    null,
                    c.i.owner,
                    200))
                .toString())
        .contains(c.id);
  }

  private record Chat(Inventory i, String id, Account customer) {
    String base() {
      return "/api/v1/conversations/" + id;
    }
  }

  private Chat chat() throws Exception {
    var i = advertised();
    return connect(i, i.tenant, enquiryBody(i, i.tenant));
  }

  private Chat connect(Inventory i, Account customer, Map<String, Object> body) throws Exception {
    return new Chat(
        i, node(call("POST", enquiryPath(i), body, customer, 200)).get("id").asText(), customer);
  }

  @Test
  void agencyAgentEnquiryAccessRequiresBothLiveLeadAndPropertyAssignment() throws Exception {
    var c=chat();var manager=account();String agency=organization(manager,"AGENCY");var agent=account();
    call("POST","/api/v1/organizations/"+agency+"/members",Map.of("email",agent.email,"role","MEMBER"),manager,201);
    var property=node(call("GET",c.i.propertyPath(),null,c.i.owner,200));
    call("PATCH",c.i.propertyPath()+"/management",Map.of("version",property.get("version").asLong(),"managerOrganizationId",agency),c.i.owner,200);
    String base="/api/v1/organizations/"+agency+"/agency";
    call("POST",base+"/assignments",Map.of("requestId",UUID.randomUUID(),"propertyId",c.i.property,"agentId",agent.id,"active",true,"message","Handle the viewing and enquiry."),manager,200);
    var client=node(call("POST",base+"/clients",Map.of("requestId",UUID.randomUUID(),"enquiryId",c.id,"details",Map.of("fullName","Enquiring customer","countryCode","KE","city","Nairobi","currency","KES","requirements","A home close to transport routes.","consentToStore",true)),manager,200));
    call("GET",c.base(),null,agent,404);
    var lead=node(call("POST",base+"/leads",Map.of("requestId",UUID.randomUUID(),"clientId",client.get("id").asText(),"title","Enquiry follow-up","agentId",agent.id,"propertyId",c.i.property,"listingId",c.i.listing,"enquiryId",c.id),manager,200));
    assertThat(current(c,agent).get("role").asText()).isEqualTo("MANAGER");
    send(c,agent,"I can assist with your enquiry.");
    call("POST",base+"/leads/"+lead.get("id").asText()+"/assign",Map.of("requestId",UUID.randomUUID(),"version",0,"message","Return lead to the agency queue."),manager,200);
    call("GET",c.base(),null,agent,404);
    call("GET",base+"/clients/"+client.get("id").asText(),null,agent,404);
  }

  private String enquiryPath(Inventory i) {
    return "/api/v1/listings/" + i.listing + "/enquiries";
  }

  private String contact(Inventory i) {
    return "/api/v1/listings/" + i.listing + "/contact";
  }

  private HashMap<String, Object> enquiryBody(Inventory i, Account actor) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "listingVersion",
            node(call("GET", contact(i), null, actor, 200)).get("listingVersion").asLong(),
            "message",
            "Interested in a viewing of this listing."));
  }

  private JsonNode current(Chat c, Account actor) throws Exception {
    return node(call("GET", c.base(), null, actor, 200));
  }

  private JsonNode messages(Chat c, Account actor) throws Exception {
    return node(call("GET", c.base() + "/messages", null, actor, 200));
  }

  private String send(Chat c, Account actor, String body) throws Exception {
    call(
        "POST",
        c.base() + "/messages",
        Map.of("requestId", UUID.randomUUID(), "body", body),
        actor,
        200);
    var list = messages(c, actor);
    return list.get(list.size() - 1).get("id").asText();
  }

  private HashMap<String, Object> conversationCommand(Chat c, Account actor) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            current(c, actor).get("version").asLong(),
            "reason",
            "Reviewed the current conversation."));
  }

  private Map<String, Object> open(String channel) {
    return Map.of("requestId", UUID.randomUUID(), "channel", channel);
  }

  private String open(Account actor, String path, String channel) throws Exception {
    return node(call("POST", path + "/conversations", open(channel), actor, 200))
        .get("id")
        .asText();
  }

  private Map<String, Object> redaction() {
    return Map.of("requestId", UUID.randomUUID(), "reason", "Withdrawing incorrectly shared text.");
  }

  private Map<String, Object> reportBody() {
    return Map.of(
        "requestId",
        UUID.randomUUID(),
        "category",
        "OTHER",
        "reason",
        "PRIVATE_REPORT_REASON: please investigate the reported text.");
  }

  private String report(Chat c, String message) throws Exception {
    return node(call(
            "POST", c.base() + "/messages/" + message + "/reports", reportBody(), c.customer, 200))
        .get("id")
        .asText();
  }

  private Account operator() throws Exception {
    Account a = account();
    promote(a);
    return a;
  }

  private void promote(Account a) throws Exception {
    jdbc.update("UPDATE app_users SET platform_role='PLATFORM_ADMIN' WHERE id=?", a.id);
    login(a);
  }

  private HashMap<String, Object> review(long version) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            version,
            "password",
            PASSWORD,
            "reason",
            "Independent review of this specifically reported message."));
  }

  private String reportAdmin(String id) {
    return "/api/v1/admin/conversation-reports/" + id;
  }

  private void claim(String id, Account actor) throws Exception {
    claim(id, actor, 0);
  }

  private void claim(String id, Account actor, long version) throws Exception {
    call("POST", reportAdmin(id) + "/claim", review(version), actor, 200);
  }

  private JsonNode inbox(Account actor) throws Exception {
    return node(call("GET", "/api/v1/me/notifications", null, actor, 200));
  }

  private String viewingPath(Chat c) {
    return c.base() + "/viewings";
  }

  private HashMap<String, Object> proposal(Chat c, Instant start, Instant end) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            current(c, c.customer).get("version").asLong(),
            "listingVersion",
            node(call("GET", contact(c.i), null, c.customer, 200)).get("listingVersion").asLong(),
            "startAt",
            start.toString(),
            "endAt",
            end.toString(),
            "meetingInstructions",
            "Meet at the publicly agreed entrance.",
            "reason",
            "Proposed viewing with the other party's confirmation required."));
  }

  private JsonNode proposeViewing(Chat c, Account actor, Instant start, Instant end)
      throws Exception {
    return node(call("POST", viewingPath(c), proposal(c, start, end), actor, 200));
  }

  private JsonNode viewing(Chat c, String id) throws Exception {
    return node(call("GET", viewingPath(c) + "/" + id, null, c.customer, 200));
  }

  private HashMap<String, Object> viewingCommand(Chat c, String id) throws Exception {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "version",
            viewing(c, id).get("version").asLong(),
            "reason",
            "Reviewed this specific viewing appointment."));
  }

  private JsonNode confirmViewing(Chat c, String id, Account actor) throws Exception {
    return node(
        call("POST", viewingPath(c) + "/" + id + "/confirm", viewingCommand(c, id), actor, 200));
  }

  private void refresh(Chat c) throws Exception {
    login(c.i.owner);
    login(c.customer);
  }

  private Inventory advertised() throws Exception {
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
    return new Inventory(owner, tenant, organization, property, unit, listing, null);
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
  static class CommunicationClockConfiguration {
    @Bean
    @Primary
    CommunicationTestClock leaseClock() {
      return new CommunicationTestClock();
    }
  }

  static class CommunicationTestClock extends Clock {
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
