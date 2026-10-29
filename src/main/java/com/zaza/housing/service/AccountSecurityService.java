package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AccountSecurityRepository;
import com.zaza.housing.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSecurityService {
  private final AccountSecurityRepository repository;
  private final PasswordEncoder encoder;
  private final SecurityCryptography crypto;
  private final SecurityAttemptService attempts;
  private final SecurityMailService mail;
  private final AuditService audit;
  private final Clock clock;

  public AccountSecurityService(
      AccountSecurityRepository repository,
      PasswordEncoder encoder,
      SecurityCryptography crypto,
      SecurityAttemptService attempts,
      SecurityMailService mail,
      AuditService audit,
      Clock clock) {
    this.repository = repository;
    this.encoder = encoder;
    this.crypto = crypto;
    this.attempts = attempts;
    this.mail = mail;
    this.audit = audit;
    this.clock = clock;
  }

  public static String hash(String raw) {
    return TokenUtils.hashToken(raw);
  }

  public static String randomToken() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static void validatePassword(String value) {
    if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw bad("Use a password of at least 12 characters and at most 72 UTF-8 bytes.");
  }

  private Map<String, Object> authenticated(AuthenticatedUser a, String token, boolean lock) {
    var account = repository.account(a.id(), lock);
    if (!Boolean.TRUE.equals(account.get("active"))
        || !repository.validSession(a.id(), token, clock.instant())) throw unauthorized();
    return account;
  }

  private void password(Map<String, Object> account, String supplied) {
    attempts.require(account.get("id").toString(), "PASSWORD_CONFIRM", 20);
    validatePassword(supplied);
    if (!encoder.matches(supplied, (String) account.get("password_hash"))) throw unauthorized();
  }

  private void proof(UUID user, String code, String recovery) {
    var factor = repository.factor(user, true);
    if (factor == null || !Boolean.TRUE.equals(factor.get("enabled")))
      throw bad("Enable an authenticator first.");
    attempts.require(user.toString(), "MFA", 10);
    if (recovery != null && !recovery.isBlank()) {
      if (!repository.consumeRecovery(user, hash(recovery.strip()), clock.instant()))
        throw unauthorized();
    } else {
      String secret =
          new String(
              crypto.decrypt(
                  "MFA|" + user + "|" + factor.get("factor_id"),
                  (String) factor.get("encrypted_secret")),
              StandardCharsets.US_ASCII);
      long counter =
          Totp.match(
              secret,
              code,
              clock.instant().getEpochSecond(),
              ((Number) factor.get("last_counter")).longValue());
      if (counter < 0) throw unauthorized();
      repository.counter(user, counter);
    }
  }

  @Transactional
  public boolean verifyLogin(UUID user, String code, String recovery) {
    var a = repository.account(user, true);
    if (!Boolean.TRUE.equals(a.get("active"))) throw unauthorized();
    var f = repository.factor(user, true);
    if (f == null || !Boolean.TRUE.equals(f.get("enabled"))) return false;
    proof(user, code, recovery);
    return true;
  }

  public AccountSecurityResponse state(AuthenticatedUser a, String token) {
    var account = authenticated(a, token, false);
    var f = repository.factor(a.id(), false);
    var sessions = repository.sessions(a.id(), token, clock.instant());
    var current =
        sessions.stream().filter(SecuritySessionResponse::current).findFirst().orElse(null);
    Instant until =
        current == null || current.mfaVerifiedAt() == null
            ? null
            : current.mfaVerifiedAt().plusSeconds(900);
    if (until != null && !until.isAfter(clock.instant())) until = null;
    return new AccountSecurityResponse(
        account.get("email_verified_at") != null,
        f != null && Boolean.TRUE.equals(f.get("enabled")),
        mail.ready(),
        crypto.configured(),
        "PLATFORM_ADMIN".equals(account.get("platform_role")),
        ((Number) account.get("security_version")).longValue(),
        until,
        sessions);
  }

  @Transactional
  public MfaEnrollmentResponse enroll(AuthenticatedUser a, String token, SecurityProofRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    var f = repository.factor(a.id(), true);
    if (f != null && Boolean.TRUE.equals(f.get("enabled")))
      throw conflict("An authenticator is already enabled.");
    UUID id = UUID.randomUUID();
    String secret = Totp.generateSecret();
    Instant until = clock.instant().plusSeconds(600);
    repository.enroll(
        a.id(),
        id,
        crypto.encrypt("MFA|" + a.id() + "|" + id, secret.getBytes(StandardCharsets.US_ASCII)),
        until,
        clock.instant());
    f = repository.factor(a.id(), false);
    audit.record(a.id(), null, "MFA_ENROLLMENT_STARTED", a.id());
    String label = URLEncoder.encode("Zaza:" + a.email(), StandardCharsets.UTF_8);
    return new MfaEnrollmentResponse(
        secret,
        "otpauth://totp/"
            + label
            + "?secret="
            + secret
            + "&issuer=Zaza&algorithm=SHA1&digits=6&period=30",
        ((Number) f.get("version")).longValue(),
        until);
  }

  @Transactional
  public MfaEnabledResponse enable(AuthenticatedUser a, String token, MfaConfirmRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    var f = repository.factor(a.id(), true);
    if (f == null
        || Boolean.TRUE.equals(f.get("enabled"))
        || ((Number) f.get("version")).longValue() != r.version()
        || !((java.sql.Timestamp) f.get("pending_until")).toInstant().isAfter(clock.instant()))
      throw conflict("Reload authenticator setup; it changed or expired.");
    attempts.require(a.id().toString(), "MFA", 10);
    String secret =
        new String(
            crypto.decrypt(
                "MFA|" + a.id() + "|" + f.get("factor_id"), (String) f.get("encrypted_secret")),
            StandardCharsets.US_ASCII);
    long counter = Totp.match(secret, r.code(), clock.instant().getEpochSecond(), -1);
    if (counter < 0) throw unauthorized();
    repository.enable(a.id(), counter, clock.instant());
    repository.revokeOthers(a.id(), token);
    repository.verified(a.id(), token, clock.instant());
    var codes = rotate(a.id());
    audit.record(a.id(), null, "MFA_ENABLED", a.id());
    return new MfaEnabledResponse(state(a, token), codes);
  }

  private List<String> rotate(UUID user) {
    var codes = new ArrayList<String>();
    for (int i = 0; i < 8; i++) codes.add(randomToken().substring(0, 22));
    repository.recovery(user, codes.stream().map(AccountSecurityService::hash).toList());
    return List.copyOf(codes);
  }

  @Transactional
  public MfaEnabledResponse recoveryCodes(
      AuthenticatedUser a, String token, SecurityProofRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    proof(a.id(), r.code(), r.recoveryCode());
    var codes = rotate(a.id());
    audit.record(a.id(), null, "MFA_RECOVERY_ROTATED", a.id());
    return new MfaEnabledResponse(state(a, token), codes);
  }

  @Transactional
  public AccountSecurityResponse stepUp(AuthenticatedUser a, String token, SecurityProofRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    proof(a.id(), r.code(), r.recoveryCode());
    repository.verified(a.id(), token, clock.instant());
    audit.record(a.id(), null, "MFA_SESSION_VERIFIED", a.id());
    return state(a, token);
  }

  @Transactional
  public AccountSecurityResponse disable(
      AuthenticatedUser a, String token, SecurityProofRequest r) {
    var account = authenticated(a, token, true);
    if ("PLATFORM_ADMIN".equals(account.get("platform_role")))
      throw bad("Platform operators must retain multi-factor authentication.");
    password(account, r.password());
    proof(a.id(), r.code(), r.recoveryCode());
    repository.disable(a.id());
    repository.revokeOthers(a.id(), token);
    repository.clearVerified(a.id());
    audit.record(a.id(), null, "MFA_DISABLED", a.id());
    return state(a, token);
  }

  @Transactional
  public void changePassword(AuthenticatedUser a, String token, SecurityPasswordRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    validatePassword(r.newPassword());
    var f = repository.factor(a.id(), true);
    if (f != null && Boolean.TRUE.equals(f.get("enabled")))
      proof(a.id(), r.code(), r.recoveryCode());
    repository.password(a.id(), encoder.encode(r.newPassword()), clock.instant());
    audit.record(a.id(), null, "PASSWORD_CHANGED", a.id());
  }

  @Transactional
  public void revokeSession(AuthenticatedUser a, String token, UUID id) {
    authenticated(a, token, true);
    repository.revoke(a.id(), id, token);
    audit.record(a.id(), null, "SESSION_REVOKED", id);
  }

  @Transactional
  public void revokeOthers(AuthenticatedUser a, String token, SecurityProofRequest r) {
    var account = authenticated(a, token, true);
    password(account, r.password());
    repository.revokeOthers(a.id(), token);
    audit.record(a.id(), null, "OTHER_SESSIONS_REVOKED", a.id());
  }

  @Transactional
  public void requestVerification(AuthenticatedUser a, String token) {
    authenticated(a, token, true);
    issue(a.id(), "VERIFY_EMAIL");
  }

  @Transactional
  public void requestRecovery(String email) {
    mail.requireReady();
    String normalized = EmailNormalizer.normalize(email);
    attempts.require(normalized, "RECOVERY_REQUEST", 5);
    UUID user = repository.byEmail(normalized);
    if (user != null) {
      repository.account(user, true);
      issue(user, "RESET_PASSWORD");
    }
  }

  private void issue(UUID user, String purpose) {
    mail.requireReady();
    if (repository.recentlySent(user, purpose, clock.instant().minusSeconds(60))) return;
    String token = randomToken();
    Instant until = clock.instant().plusSeconds(purpose.equals("VERIFY_EMAIL") ? 3600 : 900);
    repository.token(user, purpose, hash(token), until, clock.instant());
    mail.queue(user, purpose, token, until);
    audit.record(user, null, purpose + "_REQUESTED", user);
  }

  @Transactional
  public void verifyEmail(String raw) {
    var row = repository.token(hash(raw), "VERIFY_EMAIL");
    if (row == null) throw bad("This verification token is invalid or expired.");
    UUID user = (UUID) row.get("user_id");
    var account = repository.account(user, true);
    if (!Boolean.TRUE.equals(account.get("active"))
        || !repository.consumeToken(hash(raw), clock.instant()))
      throw bad("This verification token is invalid or expired.");
    repository.emailVerified(user, clock.instant());
    audit.record(user, null, "EMAIL_VERIFIED", user);
  }

  @Transactional
  public void reset(RecoveryResetRequest r) {
    validatePassword(r.newPassword());
    var row = repository.token(hash(r.token()), "RESET_PASSWORD");
    if (row == null) throw bad("This recovery token is invalid or expired.");
    UUID user = (UUID) row.get("user_id");
    var account = repository.account(user, true);
    if (!Boolean.TRUE.equals(account.get("active"))
        || !repository.consumeToken(hash(r.token()), clock.instant()))
      throw bad("This recovery token is invalid or expired.");
    var f = repository.factor(user, true);
    if (f != null && Boolean.TRUE.equals(f.get("enabled"))) proof(user, r.code(), r.recoveryCode());
    repository.password(user, encoder.encode(r.newPassword()), clock.instant());
    repository.emailVerified(user, clock.instant());
    audit.record(user, null, "PASSWORD_RECOVERED", user);
  }

  public void requireAdmin(AuthenticatedUser a, String token) {
    if (a == null
        || !"PLATFORM_ADMIN".equals(a.platformRole())
        || !repository.adminVerified(a.id(), token, clock.instant()))
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "Verify your email and multi-factor authentication in Account security before using"
              + " administration.");
  }

  private static ApiException bad(String m) {
    return new ApiException(HttpStatus.BAD_REQUEST, m);
  }

  private static ApiException conflict(String m) {
    return new ApiException(HttpStatus.CONFLICT, m);
  }

  private static ApiException unauthorized() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "Password or authentication code is incorrect, expired or already used.");
  }
}
