package com.zaza.housing.repository;

import com.zaza.housing.dto.SecuritySessionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSecurityRepository {
  private final JdbcTemplate jdbc;

  public AccountSecurityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> account(UUID user, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT"
                + " id,email,display_name,password_hash,active,platform_role,email_verified_at,security_version"
                + " FROM app_users WHERE id=?"
                + (lock ? " FOR UPDATE" : ""),
            user)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new com.zaza.housing.exception.ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Account is unavailable."));
  }

  public UUID byEmail(String email) {
    return jdbc
        .query(
            "SELECT id FROM app_users WHERE email=? AND active",
            (rs, n) -> rs.getObject(1, UUID.class),
            email)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Map<String, Object> factor(UUID user, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT * FROM account_mfa WHERE user_id=?" + (lock ? " FOR UPDATE" : ""), user)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void enroll(UUID user, UUID factor, String secret, Instant until, Instant now) {
    jdbc.update(
        "INSERT INTO account_mfa(user_id,factor_id,encrypted_secret,pending_until,updated_at)"
            + " VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET"
            + " factor_id=excluded.factor_id,encrypted_secret=excluded.encrypted_secret,pending_until=excluded.pending_until,last_counter=-1,version=account_mfa.version+1,updated_at=excluded.updated_at"
            + " WHERE NOT account_mfa.enabled",
        user,
        factor,
        secret,
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public void enable(UUID user, long counter, Instant now) {
    jdbc.update(
        "UPDATE account_mfa SET enabled=TRUE,last_counter=?,version=version+1,updated_at=? WHERE"
            + " user_id=?",
        counter,
        Timestamp.from(now),
        user);
  }

  public void counter(UUID user, long counter) {
    jdbc.update("UPDATE account_mfa SET last_counter=? WHERE user_id=?", counter, user);
  }

  public void disable(UUID user) {
    jdbc.update("DELETE FROM account_recovery_codes WHERE user_id=?", user);
    jdbc.update("DELETE FROM account_mfa WHERE user_id=?", user);
  }

  public void recovery(UUID user, List<String> hashes) {
    jdbc.update("DELETE FROM account_recovery_codes WHERE user_id=?", user);
    for (String h : hashes)
      jdbc.update("INSERT INTO account_recovery_codes(user_id,code_hash) VALUES(?,?)", user, h);
  }

  public boolean consumeRecovery(UUID user, String hash, Instant now) {
    return jdbc.update(
            "UPDATE account_recovery_codes SET consumed_at=? WHERE user_id=? AND code_hash=? AND"
                + " consumed_at IS NULL",
            Timestamp.from(now),
            user,
            hash)
        == 1;
  }

  public List<SecuritySessionResponse> sessions(UUID user, String current, Instant now) {
    return jdbc.query(
        "SELECT * FROM auth_sessions WHERE user_id=? AND expires_at>? ORDER BY created_at DESC,id"
            + " LIMIT 100",
        (rs, n) ->
            new SecuritySessionResponse(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("token_hash").equals(current),
                rs.getTimestamp("mfa_verified_at") == null
                    ? null
                    : rs.getTimestamp("mfa_verified_at").toInstant()),
        user,
        Timestamp.from(now));
  }

  public void verified(UUID user, String hash, Instant now) {
    jdbc.update(
        "UPDATE auth_sessions SET mfa_verified_at=? WHERE user_id=? AND token_hash=?",
        Timestamp.from(now),
        user,
        hash);
  }

  public boolean adminVerified(UUID user, String hash, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM auth_sessions s JOIN app_users u ON u.id=s.user_id JOIN"
                + " account_mfa m ON m.user_id=u.id AND m.enabled WHERE s.user_id=? AND"
                + " s.token_hash=? AND s.expires_at>? AND s.mfa_verified_at>? AND u.active AND"
                + " u.email_verified_at IS NOT NULL AND u.platform_role='PLATFORM_ADMIN')",
            Boolean.class,
            user,
            hash,
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(900))));
  }

  public void revokeOthers(UUID user, String current) {
    jdbc.update("DELETE FROM auth_sessions WHERE user_id=? AND token_hash<>?", user, current);
  }

  public void revoke(UUID user, UUID id, String current) {
    jdbc.update(
        "DELETE FROM auth_sessions WHERE user_id=? AND id=? AND token_hash<>?", user, id, current);
  }

  public void revokeAll(UUID user) {
    jdbc.update("DELETE FROM auth_sessions WHERE user_id=?", user);
  }

  public void password(UUID user, String hash, Instant now) {
    jdbc.update(
        "UPDATE app_users SET password_hash=?,security_version=security_version+1,updated_at=?"
            + " WHERE id=?",
        hash,
        Timestamp.from(now),
        user);
    revokeAll(user);
    jdbc.update(
        "UPDATE account_security_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL",
        Timestamp.from(now),
        user);
  }

  public void token(UUID user, String purpose, String hash, Instant until, Instant now) {
    jdbc.update(
        "UPDATE account_security_tokens SET used_at=? WHERE user_id=? AND purpose=? AND used_at IS"
            + " NULL",
        Timestamp.from(now),
        user,
        purpose);
    jdbc.update(
        "INSERT INTO account_security_tokens(token_hash,user_id,purpose,expires_at,created_at)"
            + " VALUES(?,?,?,?,?)",
        hash,
        user,
        purpose,
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public Map<String, Object> token(String hash, String purpose) {
    return jdbc
        .queryForList(
            "SELECT * FROM account_security_tokens WHERE token_hash=? AND purpose=?", hash, purpose)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean consumeToken(String hash, Instant now) {
    return jdbc.update(
            "UPDATE account_security_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL"
                + " AND expires_at>clock_timestamp()",
            Timestamp.from(now),
            hash)
        == 1;
  }

  public void emailVerified(UUID user, Instant now) {
    jdbc.update(
        "UPDATE app_users SET"
            + " email_verified_at=COALESCE(email_verified_at,?),security_version=security_version+1"
            + " WHERE id=?",
        Timestamp.from(now),
        user);
  }

  public boolean recentlySent(UUID user, String purpose, Instant since) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account_security_tokens WHERE user_id=? AND purpose=? AND"
                + " created_at>?)",
            Boolean.class,
            user,
            purpose,
            Timestamp.from(since)));
  }

  public void outbox(
      UUID id, UUID user, String purpose, String encrypted, Instant until, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " security_mail_outbox(id,user_id,purpose,encrypted_message,created_at,expires_at,next_attempt_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        user,
        purpose,
        encrypted,
        Timestamp.from(now),
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public int attempt(String subject, String purpose, long window) {
    jdbc.update("DELETE FROM security_attempt_windows WHERE window_start<?", window - 6);
    return jdbc.queryForObject(
        "INSERT INTO security_attempt_windows(subject,purpose,window_start,attempts)"
            + " VALUES(?,?,?,1) ON CONFLICT(subject,purpose,window_start) DO UPDATE SET"
            + " attempts=security_attempt_windows.attempts+1 RETURNING attempts",
        Integer.class,
        subject,
        purpose,
        window);
  }

  public List<Map<String, Object>> pendingMail() {
    return jdbc.queryForList(
        "SELECT o.*,u.email FROM security_mail_outbox o JOIN app_users u ON u.id=o.user_id WHERE"
            + " o.status='PENDING' AND o.next_attempt_at<=clock_timestamp() ORDER BY o.created_at"
            + " LIMIT 5 FOR UPDATE OF o SKIP LOCKED");
  }

  public void mailAttempt(UUID id, String status, int attempts, Instant now) {
    jdbc.update(
        "UPDATE security_mail_outbox SET status=?,attempts=?,last_attempt_at=?,next_attempt_at=?"
            + " WHERE id=?",
        status,
        attempts,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(Math.min(3600, 60L * (1L << Math.min(attempts, 5))))),
        id);
  }

  public boolean validSession(UUID user, String hash, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE user_id=? AND token_hash=? AND"
                + " expires_at>?)",
            Boolean.class,
            user,
            hash,
            Timestamp.from(now)));
  }

  public void clearVerified(UUID user) {
    jdbc.update("UPDATE auth_sessions SET mfa_verified_at=NULL WHERE user_id=?", user);
  }
}
