package com.zaza.housing.repository;

import com.zaza.housing.model.KeycloakSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KeycloakRepository {
  private final JdbcTemplate jdbc;
  public KeycloakRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Optional<UUID> user(String issuer, String subject) {
    return jdbc.query("SELECT user_id FROM keycloak_identities WHERE issuer=? AND subject=?",
        (rs, n) -> rs.getObject(1, UUID.class), issuer, subject).stream().findFirst();
  }

  public Optional<String> subject(UUID user, String issuer) {
    return jdbc.query("SELECT subject FROM keycloak_identities WHERE user_id=? AND issuer=?",
        (rs, n) -> rs.getString(1), user, issuer).stream().findFirst();
  }

  public void bind(UUID user, String issuer, String subject) {
    jdbc.update("INSERT INTO keycloak_identities(user_id,issuer,subject) VALUES(?,?,?)", user, issuer, subject);
  }

  public void save(String accessHash, String refreshHash, String encrypted,
      Instant refreshExpires, Instant sessionExpires) {
    jdbc.update("INSERT INTO keycloak_sessions(token_hash,refresh_hash,encrypted_refresh_token,refresh_expires_at,session_expires_at) VALUES(?,?,?,?,?)",
        accessHash, refreshHash, encrypted, Timestamp.from(refreshExpires), Timestamp.from(sessionExpires));
  }

  public Optional<KeycloakSession> refresh(String hash, String issuer, Instant now) {
    return session("k.refresh_hash=? AND k.refresh_expires_at>? AND k.session_expires_at>?",
        issuer, hash, Timestamp.from(now), Timestamp.from(now));
  }

  public Optional<KeycloakSession> access(String hash, String issuer) {
    return session("k.token_hash=?", issuer, hash);
  }

  public void removeRefresh(String accessHash) {
    jdbc.update("DELETE FROM keycloak_sessions WHERE token_hash=?", accessHash);
  }

  private Optional<KeycloakSession> session(String predicate, String issuer, Object... args) {
    Object[] params = new Object[args.length + 1];
    params[0] = issuer;
    System.arraycopy(args, 0, params, 1, args.length);
    return jdbc.query("SELECT s.token_hash,s.user_id,i.subject,k.encrypted_refresh_token,k.session_expires_at,s.mfa_verified_at FROM keycloak_sessions k JOIN auth_sessions s ON s.token_hash=k.token_hash JOIN app_users u ON u.id=s.user_id JOIN keycloak_identities i ON i.user_id=u.id WHERE u.active AND i.issuer=? AND "
        + predicate + " FOR UPDATE OF k,s,u",
        (rs, n) -> new KeycloakSession(rs.getString("token_hash"), rs.getObject("user_id", UUID.class),
            rs.getString("subject"), rs.getString("encrypted_refresh_token"),
            rs.getTimestamp("session_expires_at").toInstant(),
            rs.getTimestamp("mfa_verified_at") == null ? null : rs.getTimestamp("mfa_verified_at").toInstant()),
        params).stream().findFirst();
  }
}
