package com.zaza.housing.repository;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.UserAccount;
import com.zaza.housing.model.UserCredentials;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
  private static final RowMapper<UserAccount> USER =
      (rs, row) ->
          new UserAccount(
              rs.getObject("id", UUID.class),
              rs.getString("email"),
              rs.getString("display_name"),
              rs.getString("platform_role"),
              rs.getTimestamp("created_at").toInstant());
  private final JdbcTemplate jdbc;

  public AuthRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UserAccount create(String email, String displayName, String passwordHash) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_users(id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
        id,
        email,
        displayName,
        passwordHash);
    return findById(id).orElseThrow();
  }

  public Optional<UserAccount> findById(UUID id) {
    return jdbc
        .query(
            "SELECT id, email, display_name, platform_role, created_at FROM app_users WHERE id = ?"
                + " AND active",
            USER,
            id)
        .stream()
        .findFirst();
  }

  public Optional<UserCredentials> credentials(String email) {
    return jdbc
        .query(
            "SELECT id, email, display_name, platform_role, created_at, password_hash, active FROM"
                + " app_users WHERE email = ?",
            (rs, row) ->
                new UserCredentials(
                    USER.mapRow(rs, row), rs.getString("password_hash"), rs.getBoolean("active")),
            email)
        .stream()
        .findFirst();
  }

  public Optional<UserCredentials> credentialsForLogin(String email) {
    return jdbc
        .query(
            "SELECT id,email,display_name,platform_role,created_at,password_hash,active FROM"
                + " app_users WHERE email=? FOR UPDATE",
            (rs, row) ->
                new UserCredentials(
                    USER.mapRow(rs, row), rs.getString("password_hash"), rs.getBoolean("active")),
            email)
        .stream()
        .findFirst();
  }

  public void saveSession(String hash, UUID userId, Instant created, Instant expires) {
    jdbc.update(
        "INSERT INTO auth_sessions(token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?,"
            + " ?)",
        hash,
        userId,
        Timestamp.from(created),
        Timestamp.from(expires));
  }

  public Optional<AuthenticatedUser> authenticate(String hash, Instant now) {
    return jdbc
        .query(
            """
            SELECT u.id, u.email, u.platform_role FROM auth_sessions s
            JOIN app_users u ON u.id = s.user_id
            WHERE s.token_hash = ? AND s.expires_at > ? AND u.active
            """,
            (rs, row) ->
                new AuthenticatedUser(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("platform_role")),
            hash,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public void revokeSession(String hash, UUID userId) {
    jdbc.update("DELETE FROM auth_sessions WHERE token_hash = ? AND user_id = ?", hash, userId);
  }

  public List<UserAccount> listUsers(int limit, int offset) {
    return jdbc.query(
        "SELECT id, email, display_name, platform_role, created_at FROM app_users ORDER BY"
            + " created_at, id LIMIT ? OFFSET ?",
        USER,
        limit,
        offset);
  }
}
