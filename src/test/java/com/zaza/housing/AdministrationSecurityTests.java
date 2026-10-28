package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.util.Totp;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdministrationSecurityTests extends AdministrationTestSupport {
  @Test
  void totpMatchesPublishedRfc6238Sha1VectorsAndRejectsReplay() {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    long[] times = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
    String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
    for (int i = 0; i < times.length; i++)
      assertThat(Totp.code(secret, times[i] / 30, 8)).isEqualTo(expected[i]);
    String code = Totp.code(secret, 100, 6);
    assertThat(Totp.match(secret, code, 3000, 99)).isEqualTo(100);
    assertThat(Totp.match(secret, code, 3000, 100)).isEqualTo(-1);
  }

  @Test
  void operatorMfaGateIsMandatoryAndStepUpExpires() throws Exception {
    var a = user();
    jdbc.update(
        "UPDATE app_users SET platform_role='PLATFORM_ADMIN',email_verified_at=now() WHERE id=?",
        a.id());
    ok("GET", OPS + "/dashboard", null, a.token(), 403);
    var op = user(true);
    ok("GET", OPS + "/dashboard", null, op.token(), 200);
    jdbc.update(
        "UPDATE auth_sessions SET mfa_verified_at=now()-interval '16 minutes' WHERE user_id=?",
        op.id());
    ok("GET", OPS + "/dashboard", null, op.token(), 403);
    ok(
        "POST",
        SEC + "/mfa/verify",
        Map.of("password", PASSWORD, "recoveryCode", op.codes().get(0)),
        op.token(),
        200);
    ok("GET", OPS + "/dashboard", null, op.token(), 200);
    ok(
        "POST",
        SEC + "/mfa/verify",
        Map.of("password", PASSWORD, "recoveryCode", op.codes().get(0)),
        op.token(),
        401);
    assertThat(ok("GET", SEC, null, op.token(), 200).get("mfaEnabled").asBoolean()).isTrue();
  }

  @Test
  void authenticatorLoginRequiresSecondFactorAndRecoveryCodesAreSingleUse() throws Exception {
    var op = user(true);
    ok("POST", "/api/v1/auth/login", Map.of("email", op.email(), "password", PASSWORD), null, 401);
    var login =
        ok(
            "POST",
            "/api/v1/auth/login",
            Map.of("email", op.email(), "password", PASSWORD, "recoveryCode", op.codes().get(0)),
            null,
            200);
    ok("GET", OPS + "/dashboard", null, login.get("accessToken").asText(), 200);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", op.email(), "password", PASSWORD, "recoveryCode", op.codes().get(0)),
        null,
        401);
    assertThat(
            jdbc.queryForObject(
                "SELECT encrypted_secret FROM account_mfa WHERE user_id=?", String.class, op.id()))
        .doesNotContain(op.secret());
    assertThat(
            jdbc.queryForList("SELECT changes::text FROM audit_events WHERE actor_id=?", op.id())
                .toString())
        .doesNotContain(op.secret(), op.codes().get(0), PASSWORD);
  }

  @Test
  void recoveryIsGenericSingleUseAndRevokesExistingSessions() throws Exception {
    var a = user();
    var unknown =
        ok(
            "POST",
            "/api/v1/auth/recovery/request",
            Map.of("email", "missing-" + UUID.randomUUID() + "@example.test"),
            null,
            202);
    var known = ok("POST", "/api/v1/auth/recovery/request", Map.of("email", a.email()), null, 202);
    assertThat(known).isEqualTo(unknown);
    String token = mailToken(a, "RESET_PASSWORD");
    assertThat(known.toString()).doesNotContain(token);
    String replacement = "Replacement-security-password-43";
    ok(
        "POST",
        "/api/v1/auth/recovery/reset",
        Map.of("token", token, "newPassword", replacement),
        null,
        200);
    ok("GET", "/api/v1/me", null, a.token(), 401);
    ok(
        "POST",
        "/api/v1/auth/recovery/reset",
        Map.of("token", token, "newPassword", replacement),
        null,
        400);
    ok("POST", "/api/v1/auth/login", Map.of("email", a.email(), "password", PASSWORD), null, 401);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", a.email(), "password", replacement),
        null,
        200);
  }

  @Test
  void recoveryRetainsAuthenticatorAndRequiresIndependentFactor() throws Exception {
    var a = user(true);
    ok("POST", "/api/v1/auth/recovery/request", Map.of("email", a.email()), null, 202);
    String token = mailToken(a, "RESET_PASSWORD"), password = "Recovered-with-factor-password-44";
    ok(
        "POST",
        "/api/v1/auth/recovery/reset",
        Map.of("token", token, "newPassword", password),
        null,
        401);
    ok(
        "POST",
        "/api/v1/auth/recovery/reset",
        Map.of("token", token, "newPassword", password, "recoveryCode", a.codes().get(0)),
        null,
        200);
    ok("POST", "/api/v1/auth/login", Map.of("email", a.email(), "password", password), null, 401);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", a.email(), "password", password, "recoveryCode", a.codes().get(1)),
        null,
        200);
  }

  @Test
  void emailVerificationDoesNotTrustCallerSuppliedFlagsAndTokenExpires() throws Exception {
    var a = user();
    assertThat(ok("GET", SEC, null, a.token(), 200).get("emailVerified").asBoolean()).isFalse();
    ok("POST", SEC + "/email/request", Map.of(), a.token(), 200);
    String token = mailToken(a, "VERIFY_EMAIL");
    ok("POST", "/api/v1/auth/email/verify", Map.of("token", token), null, 200);
    assertThat(ok("GET", SEC, null, a.token(), 200).get("emailVerified").asBoolean()).isTrue();
    ok("POST", "/api/v1/auth/email/verify", Map.of("token", token), null, 400);
    ok("POST", "/api/v1/auth/recovery/request", Map.of("email", a.email()), null, 202);
    String reset = mailToken(a, "RESET_PASSWORD");
    jdbc.update(
        "UPDATE account_security_tokens SET expires_at=now()-interval '1 minute' WHERE user_id=?"
            + " AND purpose='RESET_PASSWORD'",
        a.id());
    ok(
        "POST",
        "/api/v1/auth/recovery/reset",
        Map.of("token", reset, "newPassword", "Never-issued-password-45"),
        null,
        400);
  }

  @Test
  void changingPasswordRevokesAllSessionsAndCannotWeakenAdminMfa() throws Exception {
    var a = user(true);
    ok(
        "POST",
        SEC + "/mfa/disable",
        Map.of("password", PASSWORD, "recoveryCode", a.codes().get(0)),
        a.token(),
        400);
    ok(
        "POST",
        SEC + "/password",
        Map.of(
            "password",
            PASSWORD,
            "newPassword",
            "Changed-security-password-45",
            "recoveryCode",
            a.codes().get(0)),
        a.token(),
        200);
    ok("GET", "/api/v1/me", null, a.token(), 401);
    ok(
        "POST",
        "/api/v1/auth/login",
        Map.of("email", a.email(), "password", "Changed-security-password-45"),
        null,
        401);
  }

  @Test
  void sessionRevocationCannotAffectAnotherAccountAndWrongConfirmationKeepsBearerValid()
      throws Exception {
    var a = user();
    var b = user();
    var current = ok("GET", SEC, null, b.token(), 200).get("sessions").get(0).get("id").asText();
    ok("POST", SEC + "/sessions/" + current + "/revoke", Map.of(), a.token(), 200);
    ok("GET", "/api/v1/me", null, b.token(), 200);
    ok(
        "POST",
        SEC + "/mfa/enroll",
        Map.of("password", "A-wrong-but-valid-password-46"),
        a.token(),
        401);
    ok("GET", "/api/v1/me", null, a.token(), 200);
  }

  @Test
  void invalidFactorAttemptsAreDurablyThrottledDespiteTransactionRollback() throws Exception {
    var a = user(true);
    for (int i = 0; i < 9; i++)
      ok(
          "POST",
          SEC + "/mfa/verify",
          Map.of("password", PASSWORD, "code", "not-a-code"),
          a.token(),
          401);
    ok(
        "POST",
        SEC + "/mfa/verify",
        Map.of("password", PASSWORD, "recoveryCode", a.codes().get(0)),
        a.token(),
        429);
  }

  @Test
  void mailDeliverySpoolsOnlyToExplicitLocalDirectoryAndEncryptionBindsContext() throws Exception {
    var a = user();
    ok("POST", SEC + "/email/request", Map.of(), a.token(), 200);
    String token = mailToken(a, "VERIFY_EMAIL");
    mail.deliver();
    try (var files = java.nio.file.Files.list(FILES.resolve("mail"))) {
      assertThat(
              files.anyMatch(
                  p -> {
                    try {
                      return java.nio.file.Files.readString(p).contains(token);
                    } catch (Exception e) {
                      return false;
                    }
                  }))
          .isTrue();
    }
    String envelope = crypto.encrypt("one-user", new byte[] {1, 2, 3});
    assertThatThrownBy(() -> crypto.decrypt("another-user", envelope))
        .isInstanceOf(com.zaza.housing.exception.ApiException.class);
  }
}
