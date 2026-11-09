package com.zaza.housing.service;

import com.zaza.housing.config.KeycloakSettings;
import com.zaza.housing.dto.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KeycloakAuthService {
  private final KeycloakSettings settings;
  private final KeycloakClientService client;
  private final ObjectProvider<JwtDecoder> decoder;
  private final AuthRepository users;
  private final KeycloakRepository identities;
  private final AccountSecurityRepository security;
  private final ObjectProvider<AccountSecurityService> accountSecurity;
  private final SecurityCryptography crypto;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final Clock clock;
  private final Duration duration;

  public KeycloakAuthService(KeycloakSettings settings, KeycloakClientService client,
      ObjectProvider<JwtDecoder> decoder, AuthRepository users, KeycloakRepository identities,
      AccountSecurityRepository security, ObjectProvider<AccountSecurityService> accountSecurity,
      SecurityCryptography crypto, PasswordEncoder encoder, AuditService audit, Clock clock,
      @Value("${zaza.auth.session-duration}") Duration duration) {
    this.settings = settings;
    this.client = client;
    this.decoder = decoder;
    this.users = users;
    this.identities = identities;
    this.security = security;
    this.accountSecurity = accountSecurity;
    this.crypto = crypto;
    this.encoder = encoder;
    this.audit = audit;
    this.clock = clock;
    this.duration = duration;
    if (settings.enabled() && !crypto.configured())
      throw new IllegalArgumentException("Keycloak sessions require the local security encryption key. Run scripts/initialize-document-key.ps1.");
  }

  public boolean enabled() { return settings.enabled(); }

  @Transactional
  public UserAccount register(String email, String password, String name) {
    AccountSecurityService.validatePassword(password);
    email = EmailNormalizer.normalize(email);
    if (users.credentials(email).isPresent()) throw conflict();
    String subject = client.createUser(email, password, name.trim());
    // Local password login is never available for these identities.
    UserAccount user = users.create(email, name.trim(), encoder.encode(UUID.randomUUID().toString()));
    identities.bind(user.id(), settings.issuer(), subject);
    audit.record(user.id(), null, "USER_REGISTERED", user.id());
    return user;
  }

  @Transactional
  public LoginResponse login(String email, String password, String code, String recovery) {
    AccountSecurityService.validatePassword(password);
    KeycloakTokenResponse tokens = client.login(EmailNormalizer.normalize(email), password);
    try {
      Jwt jwt = decode(tokens.accessToken());
      UserAccount user = resolve(jwt);
      boolean mfa = accountSecurity.getObject().verifyLogin(user.id(), code, recovery);
      var response = session(user, tokens, jwt, clock.instant().plus(duration), mfa ? clock.instant() : null, null);
      audit.record(user.id(), null, "USER_LOGGED_IN", user.id());
      return response;
    } catch (RuntimeException error) {
      try { client.logout(tokens.refreshToken()); } catch (RuntimeException ignored) { /* Retain the original failure. */ }
      throw error;
    }
  }

  private UserAccount resolve(Jwt jwt) {
    var bound = identities.user(settings.issuer(), jwt.getSubject());
    if (bound.isPresent()) return users.findById(bound.get()).orElseThrow(KeycloakAuthService::unauthorized);
    String email = jwt.getClaimAsString("email");
    if (email == null || email.length() > 254 || !email.contains("@")) throw unauthorized();
    email = EmailNormalizer.normalize(email);
    // A matching email never proves control of an existing Zaza account.
    if (users.credentials(email).isPresent()) throw conflict();
    String name = jwt.getClaimAsString("name");
    if (name == null || name.isBlank()) name = email;
    if (name.length() > 100) name = name.substring(0, 100);
    UserAccount user = users.create(email, name.strip(), encoder.encode(UUID.randomUUID().toString()));
    identities.bind(user.id(), settings.issuer(), jwt.getSubject());
    audit.record(user.id(), null, "KEYCLOAK_IDENTITY_PROVISIONED", user.id());
    return user;
  }

  @Transactional
  public LoginResponse refresh(String rawRefresh) {
    if (!enabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "Refresh tokens require Keycloak authentication mode.");
    var previous = identities.refresh(TokenUtils.hashToken(rawRefresh), settings.issuer(), clock.instant())
        .orElseThrow(KeycloakAuthService::unauthorized);
    var tokens = client.refresh(rawRefresh);
    try {
      var jwt = decode(tokens.accessToken());
      if (!previous.subject().equals(jwt.getSubject())) throw unauthorized();
      var user = users.findById(previous.userId()).orElseThrow(KeycloakAuthService::unauthorized);
      var response = session(user, tokens, jwt, previous.sessionExpiresAt(), previous.mfaVerifiedAt(), previous.tokenHash());
      audit.record(user.id(), null, "KEYCLOAK_SESSION_REFRESHED", user.id());
      return response;
    } catch (RuntimeException error) {
      try { client.logout(tokens.refreshToken()); } catch (RuntimeException ignored) { }
      throw error;
    }
  }

  private LoginResponse session(UserAccount user, KeycloakTokenResponse tokens, Jwt jwt,
      Instant sessionExpires, Instant mfaAt, String previousHash) {
    Instant now = clock.instant();
    Instant accessExpires = earlier(jwt.getExpiresAt(), sessionExpires);
    Instant refreshExpires = earlier(now.plusSeconds(tokens.refreshExpiresIn()), sessionExpires);
    if (!accessExpires.isAfter(now) || !refreshExpires.isAfter(now)) throw unauthorized();
    String hash = TokenUtils.hashToken(tokens.accessToken());
    byte[] refreshBytes = tokens.refreshToken().getBytes(StandardCharsets.UTF_8);
    String encrypted;
    try { encrypted = crypto.encrypt("KEYCLOAK_REFRESH|" + user.id() + "|" + hash, refreshBytes); }
    finally { Arrays.fill(refreshBytes, (byte) 0); }
    if (previousHash == null) {
      users.saveSession(hash, user.id(), now, accessExpires);
    } else {
      // Keep the stable session ID used by push devices and the security-session screen.
      identities.removeRefresh(previousHash);
      if (!users.rotateSession(previousHash, hash, user.id(), accessExpires)) throw unauthorized();
    }
    identities.save(hash, TokenUtils.hashToken(tokens.refreshToken()), encrypted, refreshExpires, sessionExpires);
    // Refresh preserves the original MFA proof time; it cannot extend the admin step-up window.
    if (mfaAt != null) security.verified(user.id(), hash, mfaAt);
    return new LoginResponse(tokens.accessToken(), "Bearer", accessExpires, user,
        tokens.refreshToken(), refreshExpires);
  }

  @Transactional
  public void logout(AuthenticatedUser actor, String token) {
    String hash = TokenUtils.hashToken(token);
    var session = identities.access(hash, settings.issuer()).orElseThrow(KeycloakAuthService::unauthorized);
    if (!session.userId().equals(actor.id())) throw unauthorized();
    byte[] bytes = crypto.decrypt("KEYCLOAK_REFRESH|" + actor.id() + "|" + hash, session.encryptedRefreshToken());
    try { client.logout(new String(bytes, StandardCharsets.UTF_8)); }
    finally { Arrays.fill(bytes, (byte) 0); }
    users.revokeSession(hash, actor.id());
    audit.record(actor.id(), null, "USER_LOGGED_OUT", actor.id());
  }

  public void confirmPassword(UUID user, String email, String password) {
    var subject = identities.subject(user, settings.issuer()).orElseThrow(KeycloakAuthService::unauthorized);
    var tokens = client.login(email, password);
    try {
      if (!subject.equals(decode(tokens.accessToken()).getSubject())) throw unauthorized();
    } finally { client.logout(tokens.refreshToken()); }
  }

  public void changePassword(UUID user, String password) {
    String subject = identities.subject(user, settings.issuer()).orElseThrow(KeycloakAuthService::unauthorized);
    client.changePassword(subject, password);
  }

  private Jwt decode(String value) {
    try { return decoder.getObject().decode(value); }
    catch (JwtException error) { throw unauthorized(); }
  }

  private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
  private static ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "Identity or session is unavailable. Sign in again.");
  }
  private static ApiException conflict() {
    return new ApiException(HttpStatus.CONFLICT, "An account with this email already exists. An operator must explicitly link its Keycloak identity before sign-in.");
  }
}
