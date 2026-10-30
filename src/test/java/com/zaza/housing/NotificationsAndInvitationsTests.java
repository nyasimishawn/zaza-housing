package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.repository.NotificationWorkspaceRepository;
import com.zaza.housing.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
    properties = {
      "zaza.notifications.email-mode=local",
      "zaza.notifications.sms-mode=local",
      "zaza.notifications.push-mode=local",
      "zaza.notifications.poll-ms=3600000",
      "zaza.notifications.reminder-poll-ms=3600000"
    })
class NotificationsAndInvitationsTests extends AdministrationTestSupport {
  static final String N = "/api/v1/me/notifications", IN = "/api/v1/me/team-invitations";
  @Autowired NotificationWorkspaceRepository notifications;
  @Autowired NotificationDeliveryService delivery;
  @Autowired NotificationWorkspaceService workspace;
  @Autowired com.zaza.housing.repository.NotificationDeliveryRepository deliveryRepository;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  static final Path OUT = FILES.resolve("notifications");

  @DynamicPropertySource
  static void notificationsPath(DynamicPropertyRegistry r) {
    r.add("zaza.notifications.local-directory", () -> OUT.toString());
  }

  Account verified() throws Exception {
    var a = user();
    jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?", a.id());
    return a;
  }

  String org(Account a) throws Exception {
    return ok(
            "POST",
            "/api/v1/organizations",
            Map.of("name", "Invitation test agency", "type", "AGENCY"),
            a.token(),
            201)
        .get("id")
        .asText();
  }

  String invites(String org) {
    return "/api/v1/organizations/" + org + "/invitations";
  }

  Map<String, Object> command(long v) {
    return Map.of("version", v);
  }

  JsonNode invite(Account a, String org, String email, String role) throws Exception {
    return ok(
        "POST",
        invites(org),
        Map.of("requestId", UUID.randomUUID(), "email", email, "role", role),
        a.token(),
        201);
  }

  JsonNode response(Account a, JsonNode i, String action, int status) throws Exception {
    return ok(
        "POST",
        IN + "/" + i.get("id").asText() + "/" + action,
        command(i.get("version").asLong()),
        a.token(),
        status);
  }

  String invitationToken(String id) {
    var row =
        jdbc.queryForMap(
            "SELECT id,encrypted_payload FROM notification_deliveries WHERE invitation_id=? AND"
                + " status='PENDING' ORDER BY created_at DESC LIMIT 1",
            UUID.fromString(id));
    String text =
        new String(
            crypto.decrypt("DELIVERY|" + row.get("id"), (String) row.get("encrypted_payload")),
            StandardCharsets.UTF_8);
    var m = java.util.regex.Pattern.compile("(?m)^[A-Za-z0-9_-]{43}$").matcher(text);
    assertThat(m.find()).isTrue();
    return m.group();
  }

  Map<String, Object> preferences(
      Account a, String category, boolean email, boolean sms, boolean push) throws Exception {
    var p = ok("GET", N + "/settings", null, a.token(), 200);
    var categories = new ArrayList<Map<String, Object>>();
    for (var item : p.get("categories"))
      categories.add(
          new HashMap<>(
              Map.of(
                  "category",
                  item.get("category").asText(),
                  "inApp",
                  true,
                  "email",
                  item.get("category").asText().equals(category) && email,
                  "sms",
                  item.get("category").asText().equals(category) && sms,
                  "push",
                  item.get("category").asText().equals(category) && push)));
    var body = new HashMap<String, Object>();
    body.put("version", p.get("version").asLong());
    body.put("timezone", "Africa/Nairobi");
    body.put("emailMode", "INSTANT");
    body.put("categories", categories);
    return body;
  }

  void alert(Account a, String type) {
    notifications.event(
        a.id(),
        "REMINDER",
        UUID.randomUUID(),
        type,
        "Synthetic private update",
        "test:" + UUID.randomUUID(),
        Instant.now());
  }

  String recentDelivery(Account a) {
    return jdbc.queryForObject(
            "SELECT id FROM notification_deliveries WHERE user_id=? ORDER BY created_at DESC LIMIT"
                + " 1",
            UUID.class,
            a.id())
        .toString();
  }

  @Test
  void invitationDoesNotGrantAccessBeforeVerifiedRecipientAcceptsAndCannotBeReplayed()
      throws Exception {
    var owner = verified();
    var target = user();
    String org = org(owner);
    var i = invite(owner, org, target.email().toUpperCase(), "MEMBER");
    assertThat(i.get("email").asText()).isEqualTo(target.email());
    assertThat(i.toString()).doesNotContain("tokenHash", "token", "password", "invitedBy");
    ok("GET", "/api/v1/organizations/" + org, null, target.token(), 404);
    ok("GET", IN, null, target.token(), 403);
    ok("GET", IN + "/" + i.get("id").asText(), null, target.token(), 403);
    response(target, i, "accept", 403);
    jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?", target.id());
    response(target, i, "accept", 200);
    assertThat(
            ok("GET", "/api/v1/organizations/" + org, null, target.token(), 200)
                .get("role")
                .asText())
        .isEqualTo("MEMBER");
    response(target, i, "accept", 409);
    assertThat(
            ok(
                    "GET",
                    invites(org) + "/" + i.get("id").asText() + "/history",
                    null,
                    owner.token(),
                    200)
                .size())
        .isEqualTo(2);
  }

  @Test
  void invitationWorksForNewAccountCreatedAfterInvitation() throws Exception {
    var owner = verified();
    String org = org(owner);
    String email = "ops-" + UUID.randomUUID() + "@example.test";
    var i = invite(owner, org, email, "ADMIN");
    String code = invitationToken(i.get("id").asText());
    var registered =
        ok(
            "POST",
            "/api/v1/auth/register",
            Map.of("email", email, "password", PASSWORD, "displayName", "New invitee"),
            null,
            201);
    UUID id = UUID.fromString(registered.get("id").asText());
    jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?", id);
    String token =
        ok("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, 200)
            .get("accessToken")
            .asText();
    assertThat(ok("GET", IN, null, token, 200).size()).isEqualTo(1);
    ok("POST", IN + "/redeem", Map.of("token", code), token, 200);
    assertThat(ok("GET", "/api/v1/organizations/" + org, null, token, 200).get("role").asText())
        .isEqualTo("ADMIN");
  }

  @Test
  void wrongAccountCannotViewHistoryOrRedeemInvitation() throws Exception {
    var owner = verified();
    var target = verified();
    var outsider = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    assertThat(ok("GET", IN, null, outsider.token(), 200).size()).isZero();
    response(outsider, i, "accept", 404);
    ok("GET", IN + "/" + i.get("id").asText() + "/history", null, outsider.token(), 404);
    ok(
        "POST",
        IN + "/redeem",
        Map.of("token", invitationToken(i.get("id").asText())),
        outsider.token(),
        404);
  }

  @Test
  void duplicateRequestIsIdempotentAndCannotChangeInvitation() throws Exception {
    var owner = verified();
    String org = org(owner);
    var r =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "email",
                "new-" + UUID.randomUUID() + "@example.test",
                "role",
                "MEMBER"));
    var first = ok("POST", invites(org), r, owner.token(), 201);
    assertThat(ok("POST", invites(org), r, owner.token(), 201).get("id"))
        .isEqualTo(first.get("id"));
    r.put("role", "ADMIN");
    ok("POST", invites(org), r, owner.token(), 409);
  }

  @Test
  void duplicatePendingAndExistingMemberAreRejected() throws Exception {
    var owner = verified();
    var target = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    ok(
        "POST",
        invites(org),
        Map.of("requestId", UUID.randomUUID(), "email", target.email(), "role", "MEMBER"),
        owner.token(),
        409);
    response(target, i, "accept", 200);
    ok(
        "POST",
        invites(org),
        Map.of("requestId", UUID.randomUUID(), "email", target.email(), "role", "ADMIN"),
        owner.token(),
        409);
  }

  @Test
  void cancellationAndExpiryInvalidateCodesAndPendingEmail() throws Exception {
    var owner = verified();
    var target = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    String code = invitationToken(i.get("id").asText());
    ok(
        "POST",
        invites(org) + "/" + i.get("id").asText() + "/cancel",
        command(0),
        owner.token(),
        200);
    ok("POST", IN + "/redeem", Map.of("token", code), target.token(), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE invitation_id=?",
                String.class,
                UUID.fromString(i.get("id").asText())))
        .isEqualTo("CANCELLED");
    var next = invite(owner, org, target.email(), "MEMBER");
    jdbc.update(
        "UPDATE team_invitations SET expires_at=now()-interval '1 second' WHERE id=?",
        UUID.fromString(next.get("id").asText()));
    response(target, next, "accept", 409);
    assertThat(ok("GET", IN + "?status=EXPIRED", null, target.token(), 200).size()).isEqualTo(1);
  }

  @Test
  void resendRotatesSecretEnforcesCooldownAndOptimisticVersion() throws Exception {
    var owner = verified();
    var target = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    String id = i.get("id").asText(),
        old = invitationToken(id),
        path = invites(org) + "/" + id + "/resend";
    ok("POST", path, command(0), owner.token(), 429);
    jdbc.update(
        "UPDATE team_invitations SET last_sent_at=now()-interval '2 minutes' WHERE id=?",
        UUID.fromString(id));
    var next = ok("POST", path, command(0), owner.token(), 200);
    assertThat(next.get("sendCount").asInt()).isEqualTo(2);
    ok("POST", IN + "/redeem", Map.of("token", old), target.token(), 404);
    ok("POST", path, command(0), owner.token(), 409);
    ok("POST", IN + "/redeem", Map.of("token", invitationToken(id)), target.token(), 200);
  }

  @Test
  void demotedInviterCannotConferAccessThroughOldInvitation() throws Exception {
    var owner = verified();
    var admin = verified();
    var target = verified();
    String org = org(owner);
    ok(
        "POST",
        "/api/v1/organizations/" + org + "/members",
        Map.of("email", admin.email(), "role", "ADMIN"),
        owner.token(),
        201);
    var i = invite(admin, org, target.email(), "MEMBER");
    ok(
        "PATCH",
        "/api/v1/organizations/" + org + "/members/" + admin.id(),
        Map.of("role", "MEMBER"),
        owner.token(),
        200);
    response(target, i, "accept", 409);
    ok("GET", invites(org), null, admin.token(), 403);
  }

  @Test
  void administratorsCannotInviteOwnershipOrAdministratorRoleAndMembersCannotList()
      throws Exception {
    var owner = verified();
    var admin = verified();
    String org = org(owner);
    ok(
        "POST",
        "/api/v1/organizations/" + org + "/members",
        Map.of("email", admin.email(), "role", "ADMIN"),
        owner.token(),
        201);
    for (String role : List.of("ADMIN", "OWNER"))
      ok(
          "POST",
          invites(org),
          Map.of("requestId", UUID.randomUUID(), "email", "someone@example.test", "role", role),
          admin.token(),
          403);
    ok(
        "POST",
        invites(org),
        Map.of("requestId", UUID.randomUUID(), "email", "someone@example.test", "role", "OWNER"),
        owner.token(),
        403);
  }

  @Test
  void concurrentAcceptanceCreatesOnlyOneMembership() throws Exception {
    var owner = verified();
    var target = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    String path = IN + "/" + i.get("id").asText() + "/accept";
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> call("POST", path, command(0), target.token()).statusCode());
      var b = executor.submit(() -> call("POST", path, command(0), target.token()).statusCode());
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    }
  }

  @Test
  void decliningNeverGrantsBusinessAccess() throws Exception {
    var owner = verified();
    var target = verified();
    String org = org(owner);
    var i = invite(owner, org, target.email(), "MEMBER");
    assertThat(response(target, i, "decline", 200).get("status").asText()).isEqualTo("DECLINED");
    ok("GET", "/api/v1/organizations/" + org, null, target.token(), 404);
  }

  @Test
  void settingsDefaultToInAppOnlyAndRejectStaleAndMalformedPreferenceWrites() throws Exception {
    var a = verified();
    var p = ok("GET", N + "/settings", null, a.token(), 200);
    assertThat(p.get("categories").size()).isEqualTo(11);
    for (var row : p.get("categories")) {
      assertThat(row.get("inApp").asBoolean()).isTrue();
      assertThat(row.get("email").asBoolean()).isFalse();
    }
    var r = preferences(a, "REMINDERS", true, false, false);
    ok("PUT", N + "/settings", r, a.token(), 200);
    ok("PUT", N + "/settings", r, a.token(), 409);
    r = preferences(a, "REMINDERS", true, false, false);
    r.put("timezone", "MadeUp/Moon");
    ok("PUT", N + "/settings", r, a.token(), 400);
    r.put("timezone", "Africa/Nairobi");
    r.put("quietStart", 60);
    ok("PUT", N + "/settings", r, a.token(), 400);
  }

  @Test
  void emailAndSmsNeedVerifiedDestinationAndNoCrossAccountPreferencesLeak() throws Exception {
    var a = user();
    var b = verified();
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 403);
    ok("PUT", N + "/settings", preferences(b, "REMINDERS", false, true, false), b.token(), 403);
    ok("PUT", N + "/settings", preferences(b, "REMINDERS", true, false, false), b.token(), 200);
    assertThat(ok("GET", N + "/settings", null, a.token(), 200).get("version").asLong()).isZero();
  }

  @Test
  void localEmailIsDurableDeduplicatedAndAcceptedWithoutSensitivePreview() throws Exception {
    var a = verified();
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    delivery.deliver();
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("ACCEPTED");
    assertThat(Files.readString(OUT.resolve(id + ".txt")))
        .doesNotContain("Synthetic private update")
        .contains("Open Notifications");
    assertThat(ok("GET", N + "/deliveries/" + id + "/attempts", null, a.token(), 200).size())
        .isEqualTo(1);
  }

  @Test
  void disablingPreferenceCancelsAlreadyQueuedDelivery() throws Exception {
    var a = verified();
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", false, false, false), a.token(), 200);
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("CANCELLED");
  }

  @Test
  void quietHoursDeferDeliveryAndRespectOvernightZones() throws Exception {
    var a = verified();
    var r = preferences(a, "REMINDERS", true, false, false);
    var now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"));
    int minute = now.getHour() * 60 + now.getMinute();
    r.put("quietStart", (minute + 1439) % 1440);
    r.put("quietEnd", (minute + 10) % 1440);
    ok("PUT", N + "/settings", r, a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT error_code FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("QUIET_HOURS");
    assertThat(
            NotificationDeliveryService.quietEnd(
                Map.of("timezone", "Africa/Nairobi", "quiet_start", 1320, "quiet_end", 420),
                Instant.parse("2026-10-30T20:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-31T04:00:00Z"));
  }

  @Test
  void dailyEmailCombinesMultipleNotificationsIntoOnePendingEnvelope() throws Exception {
    var a = verified();
    var r = preferences(a, "REMINDERS", true, false, false);
    r.put("emailMode", "DAILY");
    ok("PUT", N + "/settings", r, a.token(), 200);
    alert(a, "REMINDER_DUE");
    alert(a, "REMINDER_DUE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE user_id=? AND purpose='DIGEST'",
                Integer.class,
                a.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT extract(hour from available_at AT TIME ZONE 'Africa/Nairobi')::int FROM"
                    + " notification_deliveries WHERE user_id=? AND purpose='DIGEST'",
                Integer.class,
                a.id()))
        .isEqualTo(8);
  }

  @Test
  void supportUpdatesUseTheSamePreferenceAndDeliveryPipeline() throws Exception {
    var a = verified();
    ok("PUT", N + "/settings", preferences(a, "SUPPORT", true, false, false), a.token(), 200);
    createCase(a, "SUPPORT", null);
    var updates = ok("GET", N + "?category=SUPPORT", null, a.token(), 200);
    assertThat(updates.size()).isEqualTo(1);
    assertThat(updates.get(0).get("message").asText())
        .isEqualTo(
            "A support case you follow was updated. Open your private support updates for"
                + " details.");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE user_id=? AND"
                    + " category='SUPPORT'",
                Integer.class,
                a.id()))
        .isEqualTo(1);
  }

  @Test
  void savedSearchMatchesBridgeToNotificationChannelsWithoutReplayingOldAlerts() throws Exception {
    var a = verified();
    ok("PUT", N + "/settings", preferences(a, "DISCOVERY", true, false, false), a.token(), 200);
    var search =
        ok(
            "POST",
            "/api/v1/me/discovery/searches",
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "name",
                "Notification bridge test",
                "frequency",
                "HOURLY",
                "query",
                Map.of("kind", "PROPERTY")),
            a.token(),
            200);
    UUID id = UUID.fromString(search.get("id").asText());
    UUID generation =
        jdbc.queryForObject(
            "SELECT generation FROM discovery_saved_searches WHERE id=?", UUID.class, id);
    jdbc.update(
        "INSERT INTO discovery_alerts(id,user_id,search_id,generation,target_id,created_at)"
            + " VALUES(?,?,?,?,?,now())",
        UUID.randomUUID(),
        a.id(),
        id,
        generation,
        UUID.randomUUID());
    assertThat(ok("GET", N + "?category=DISCOVERY", null, a.token(), 200).size()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE user_id=? AND"
                    + " category='DISCOVERY'",
                Integer.class,
                a.id()))
        .isEqualTo(1);
  }

  @Test
  void phoneVerificationPersistsBadAttemptsLimitsGuessingAndHidesNumber() throws Exception {
    var a = verified();
    String number =
        "+254700" + String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
    ok("POST", N + "/phone/challenge", Map.of("phone", number), a.token(), 202);
    ok("POST", N + "/phone/challenge", Map.of("phone", number), a.token(), 429);
    var row =
        jdbc.queryForMap(
            "SELECT id,encrypted_payload FROM notification_deliveries WHERE user_id=? AND"
                + " purpose='PHONE_CODE'",
            a.id());
    String codeText =
        new String(
            crypto.decrypt("DELIVERY|" + row.get("id"), (String) row.get("encrypted_payload")),
            StandardCharsets.UTF_8);
    String code = codeText.substring(codeText.indexOf("is ") + 3, codeText.indexOf(". It"));
    String wrong = code.equals("000000") ? "000001" : "000000";
    ok("POST", N + "/phone/verify", Map.of("code", wrong), a.token(), 400);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM notification_phone_contacts WHERE user_id=?",
                Integer.class,
                a.id()))
        .isEqualTo(1);
    ok("POST", N + "/phone/verify", Map.of("code", code), a.token(), 204);
    ok("POST", N + "/phone/verify", Map.of("code", code), a.token(), 409);
    var settings = ok("GET", N + "/settings", null, a.token(), 200);
    assertThat(settings.get("phoneVerified").asBoolean()).isTrue();
    assertThat(settings.toString()).doesNotContain(number, code);
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", false, true, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    ok("DELETE", N + "/phone", null, a.token(), 204);
    delivery.deliver();
    assertThat(ok("GET", N + "/settings", null, a.token(), 200).get("phoneVerified").asBoolean())
        .isFalse();
  }

  @Test
  void pushDevicesStoreEncryptedTokensAndCanBeRevokedOnlyByOwner() throws Exception {
    var a = verified();
    var b = verified();
    String token = "Synthetic-fcm-" + UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    var d =
        ok(
            "POST",
            N + "/devices",
            Map.of(
                "installationId",
                installation,
                "platform",
                "ANDROID",
                "label",
                "Presentation phone",
                "token",
                token),
            a.token(),
            200);
    assertThat(d.toString()).doesNotContain(token, "encryptedToken");
    assertThat(
            jdbc.queryForObject(
                "SELECT encrypted_token FROM notification_devices WHERE id=?",
                String.class,
                UUID.fromString(d.get("id").asText())))
        .doesNotContain(token);
    ok("DELETE", N + "/devices/" + d.get("id").asText(), null, b.token(), 404);
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", false, false, true), a.token(), 200);
    alert(a, "REMINDER_DUE");
    ok("DELETE", N + "/devices/" + d.get("id").asText(), null, a.token(), 204);
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE user_id=?", String.class, a.id()))
        .isEqualTo("CANCELLED");
  }

  @Test
  void rebindingPushTokenToAnotherAccountDeactivatesOldOwner() throws Exception {
    var a = verified();
    var b = verified();
    String token = "Synthetic-fcm-" + UUID.randomUUID();
    ok(
        "POST",
        N + "/devices",
        Map.of(
            "installationId",
            UUID.randomUUID(),
            "platform",
            "IOS",
            "label",
            "Shared phone",
            "token",
            token),
        a.token(),
        200);
    ok(
        "POST",
        N + "/devices",
        Map.of(
            "installationId",
            UUID.randomUUID(),
            "platform",
            "IOS",
            "label",
            "Shared phone",
            "token",
            token),
        b.token(),
        200);
    assertThat(ok("GET", N + "/devices", null, a.token(), 200).get(0).get("active").asBoolean())
        .isFalse();
  }

  @Test
  void inboxFiltersArchiveRestoreBulkCutoffAndOwnershipWork() throws Exception {
    var a = verified();
    var b = verified();
    alert(a, "REMINDER_DUE");
    alert(a, "TEAM_INVITATION_RECEIVED");
    var items = ok("GET", N + "?category=REMINDERS", null, a.token(), 200);
    assertThat(items.size()).isEqualTo(1);
    String id = items.get(0).get("id").asText();
    ok("POST", N + "/" + id + "/archive", null, b.token(), 404);
    ok("POST", N + "/" + id + "/archive", null, a.token(), 200);
    assertThat(ok("GET", N + "?archived=true", null, a.token(), 200).size()).isEqualTo(1);
    ok("POST", N + "/" + id + "/restore", null, a.token(), 200);
    ok("POST", N + "/read-all", Map.of("before", Instant.now().toString()), a.token(), 200);
    assertThat(ok("GET", N + "/summary", null, a.token(), 200).get("unread").asInt()).isZero();
    assertThat(ok("GET", N, null, b.token(), 200).size()).isZero();
  }

  @Test
  void oneTimeAndRecurringRemindersAreIdempotentAndDoNotEmitDuplicateEvents() throws Exception {
    var a = verified();
    var r =
        new HashMap<String, Object>(
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "title",
                "Check a viewing",
                "dueAt",
                Instant.now().plusSeconds(3600).toString(),
                "repeatRule",
                "DAILY"));
    var reminder = ok("POST", N + "/reminders", r, a.token(), 201);
    String id = reminder.get("id").asText();
    assertThat(ok("POST", N + "/reminders", r, a.token(), 201).get("id").asText()).isEqualTo(id);
    jdbc.update(
        "UPDATE notification_reminders SET due_at=now()-interval '2 days' WHERE id=?",
        UUID.fromString(id));
    workspace.remindersDue();
    workspace.remindersDue();
    assertThat(ok("GET", N + "?category=REMINDERS", null, a.token(), 200).size()).isEqualTo(1);
    var current = ok("GET", N + "/reminders", null, a.token(), 200).get(0);
    assertThat(Instant.parse(current.get("dueAt").asText())).isAfter(Instant.now());
    ok("POST", N + "/reminders/" + id + "/cancel", command(0), a.token(), 409);
    ok(
        "POST",
        N + "/reminders/" + id + "/cancel",
        command(current.get("version").asLong()),
        a.token(),
        200);
  }

  @Test
  void reminderEditsArePrivateVersionedAndRejectInvalidTimes() throws Exception {
    var a = verified();
    var b = verified();
    var body =
        Map.of(
            "requestId",
            UUID.randomUUID(),
            "title",
            "Review rent statement",
            "dueAt",
            Instant.now().plusSeconds(7200).toString(),
            "repeatRule",
            "NONE");
    var reminder = ok("POST", N + "/reminders", body, a.token(), 201);
    String id = reminder.get("id").asText();
    var edit =
        new HashMap<String, Object>(
            Map.of(
                "version",
                0,
                "title",
                "Review updated statement",
                "dueAt",
                Instant.now().plusSeconds(10800).toString(),
                "repeatRule",
                "WEEKLY"));
    ok("PUT", N + "/reminders/" + id, edit, b.token(), 404);
    ok("PUT", N + "/reminders/" + id, edit, a.token(), 200);
    ok("PUT", N + "/reminders/" + id, edit, a.token(), 409);
    edit.put("version", 1);
    edit.put("dueAt", Instant.now().minusSeconds(1).toString());
    ok("PUT", N + "/reminders/" + id, edit, a.token(), 400);
  }

  @Test
  void deliveryHistoryAndRetryCannotExposeOrReplayAnotherAccountsNotifications() throws Exception {
    var a = verified();
    var b = verified();
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    assertThat(ok("GET", N + "/deliveries", null, b.token(), 200).size()).isZero();
    assertThat(ok("GET", N + "/deliveries/" + id + "/attempts", null, b.token(), 200).size())
        .isZero();
    ok("POST", N + "/deliveries/" + id + "/retry", null, b.token(), 409);
    delivery.deliver();
    ok("POST", N + "/deliveries/" + id + "/retry", null, a.token(), 409);
    assertThat(ok("GET", N + "/deliveries", null, a.token(), 200).toString())
        .doesNotContain("encrypted", "@example.test");
  }

  @Test
  void deliveryAndInvitationHistoryIsAppendOnly() throws Exception {
    var a = verified();
    var t = verified();
    String org = org(a);
    var invitation = invite(a, org, t.email(), "MEMBER");
    UUID id = UUID.fromString(invitation.get("id").asText());
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM team_invitation_events WHERE invitation_id=?", id))
        .isInstanceOf(Exception.class);
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String d = recentDelivery(a);
    delivery.deliver();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM notification_delivery_attempts WHERE delivery_id=?",
                    UUID.fromString(d)))
        .isInstanceOf(Exception.class);
  }

  @Test
  void providerFailureBacksOffStopsAfterFiveAttemptsAndAllowsBoundedManualRetry() throws Exception {
    var a = verified();
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", true, false, false), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    Path blocked = FILES.resolve("blocked-delivery-" + UUID.randomUUID());
    Files.createDirectories(blocked.getParent());
    Files.writeString(blocked, "synthetic fixture");
    var transport =
        new NotificationDeliveryTransport(
            json, "local", "off", "off", "", 587, "", "", "", "", "", "", "", blocked.toString());
    var failing =
        new NotificationDeliveryService(
            deliveryRepository, transport, crypto, Clock.systemUTC(), transactions);
    for (int index = 0; index < 5; index++) {
      jdbc.update(
          "UPDATE notification_deliveries SET available_at=now()-interval '1 second' WHERE id=?",
          UUID.fromString(id));
      failing.deliver();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("FAILED");
    assertThat(ok("GET", N + "/deliveries/" + id + "/attempts", null, a.token(), 200).size())
        .isEqualTo(5);
    ok("POST", N + "/deliveries/" + id + "/retry", null, a.token(), 409);
    jdbc.update(
        "UPDATE notification_deliveries SET last_attempt_at=now()-interval '2 minutes' WHERE id=?",
        UUID.fromString(id));
    ok("POST", N + "/deliveries/" + id + "/retry", null, a.token(), 202);
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("ACCEPTED");
  }

  @Test
  void revokedSessionCannotContinueReceivingPushNotifications() throws Exception {
    var a = verified();
    ok(
        "POST",
        N + "/devices",
        Map.of(
            "installationId",
            UUID.randomUUID(),
            "platform",
            "ANDROID",
            "label",
            "Test phone",
            "token",
            "Synthetic-fcm-" + UUID.randomUUID()),
        a.token(),
        200);
    ok("PUT", N + "/settings", preferences(a, "REMINDERS", false, false, true), a.token(), 200);
    alert(a, "REMINDER_DUE");
    String id = recentDelivery(a);
    ok("POST", "/api/v1/auth/logout", null, a.token(), 204);
    delivery.deliver();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("CANCELLED");
  }

  @Test
  void operatorQueueHealthIsRestrictedAndDoesNotContainRecipients() throws Exception {
    var ordinary = verified();
    ok("GET", OPS + "/notification-deliveries/summary", null, ordinary.token(), 403);
    var operator = user(true);
    var result = ok("GET", OPS + "/notification-deliveries/summary", null, operator.token(), 200);
    assertThat(result.has("queues")).isTrue();
    assertThat(result.toString()).doesNotContain("@", "encrypted", "token");
  }
}
