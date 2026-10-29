package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AdministrationRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AdministrationRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Map<String, Object> operation(UUID request) {
    return jdbc
        .queryForList("SELECT * FROM administration_commands WHERE request_id=?", request)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void operation(
      UUID request, UUID actor, String action, UUID target, String fingerprint, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " administration_commands(request_id,actor_id,action,target_id,fingerprint,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        request,
        actor,
        action,
        target,
        fingerprint,
        Timestamp.from(now));
  }

  public UUID event(
      UUID actor,
      String kind,
      UUID target,
      String action,
      String message,
      boolean internal,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " administration_events(id,actor_id,target_kind,target_id,action,message,internal,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        actor,
        kind,
        target,
        action,
        message,
        internal,
        Timestamp.from(now));
    return id;
  }

  public List<Map<String, Object>> history(
      String kind, UUID target, boolean internal, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT id,actor_id AS \"actorId\",target_kind AS \"targetKind\",target_id AS"
            + " \"targetId\",action,message,internal,created_at AS \"createdAt\" FROM"
            + " administration_events WHERE target_kind=? AND target_id=? AND (? OR NOT internal)"
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        kind,
        target,
        internal,
        limit,
        offset);
  }

  public List<AdminUserResponse> users(String q, Boolean active, int limit, int offset) {
    return jdbc.query(
        "SELECT u.*,EXISTS(SELECT 1 FROM account_mfa f WHERE f.user_id=u.id AND f.enabled) AS"
            + " mfa_enabled FROM app_users u WHERE (?='' OR strpos(lower(u.email||'"
            + " '||u.display_name),lower(?))>0) AND (?::boolean IS NULL OR u.active=?::boolean)"
            + " ORDER BY u.created_at DESC,u.id LIMIT ? OFFSET ?",
        (rs, n) ->
            new AdminUserResponse(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("platform_role"),
                rs.getBoolean("active"),
                rs.getTimestamp("email_verified_at") != null,
                rs.getBoolean("mfa_enabled"),
                rs.getLong("security_version"),
                rs.getTimestamp("created_at").toInstant()),
        q,
        q,
        active,
        active,
        limit,
        offset);
  }

  public Map<String, Object> action(UUID id, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT a.*,u.email AS \"targetEmail\",u.display_name AS \"targetName\" FROM"
                + " account_action_requests a JOIN app_users u ON u.id=a.target_user_id WHERE"
                + " a.id=?"
                + (lock ? " FOR UPDATE OF a" : ""),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Map<String, Object>> actions(String status, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT a.id,a.target_user_id AS \"targetUserId\",u.display_name AS \"targetName\",u.email"
            + " AS \"targetEmail\",a.target_version AS"
            + " \"targetVersion\",a.action,a.reason,a.requested_by AS \"requestedBy\",a.reviewed_by"
            + " AS \"reviewedBy\",a.decision_reason AS"
            + " \"decisionReason\",a.status,a.version,a.created_at AS \"createdAt\" FROM"
            + " account_action_requests a JOIN app_users u ON u.id=a.target_user_id WHERE (?::text"
            + " IS NULL OR a.status=?) ORDER BY a.created_at DESC,a.id LIMIT ? OFFSET ?",
        status,
        status,
        limit,
        offset);
  }

  public UUID propose(UUID actor, AccountActionRequest r, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " account_action_requests(id,target_user_id,target_version,action,reason,requested_by,status,created_at)"
            + " VALUES(?,?,?,?,?,?,'PENDING',?)",
        id,
        r.userId(),
        r.version(),
        r.action(),
        r.reason().trim(),
        actor,
        Timestamp.from(now));
    return id;
  }

  public void decide(UUID id, UUID actor, String status, String reason, Instant now) {
    jdbc.update(
        "UPDATE account_action_requests SET"
            + " reviewed_by=?,decision_reason=?,status=?,version=version+1,reviewed_at=? WHERE"
            + " id=?",
        actor,
        reason,
        status,
        Timestamp.from(now),
        id);
  }

  public void withdraw(UUID id, String reason, Instant now) {
    jdbc.update(
        "UPDATE account_action_requests SET"
            + " decision_reason=?,status='WITHDRAWN',version=version+1,reviewed_at=? WHERE id=?",
        reason,
        Timestamp.from(now),
        id);
  }

  public void active(UUID user, boolean active, Instant now) {
    jdbc.update(
        "UPDATE app_users SET active=?,security_version=security_version+1,updated_at=? WHERE id=?",
        active,
        Timestamp.from(now),
        user);
    jdbc.update("DELETE FROM auth_sessions WHERE user_id=?", user);
  }

  public List<Map<String, Object>> audit(String action, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT id,actor_id AS \"actorId\",organization_id AS \"organizationId\",action,entity_id"
            + " AS \"entityId\",created_at AS \"createdAt\" FROM audit_events WHERE (?::text IS"
            + " NULL OR action=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        action,
        action,
        limit,
        offset);
  }

  public Map<String, Long> counts() {
    return jdbc.queryForObject(
        "SELECT (SELECT count(*) FROM app_users WHERE active) AS active_users,(SELECT count(*) FROM"
            + " app_users WHERE NOT active) AS restricted_users,(SELECT count(*) FROM"
            + " account_action_requests WHERE status='PENDING') AS pending_account_actions,(SELECT"
            + " count(*) FROM support_cases WHERE status NOT IN('RESOLVED','CLOSED')) AS"
            + " open_cases,(SELECT count(*) FROM support_cases WHERE status='ESCALATED') AS"
            + " escalated_cases,(SELECT count(*) FROM support_cases WHERE status NOT"
            + " IN('WAITING_CUSTOMER','RESOLVED','CLOSED') AND first_response_at IS NULL AND"
            + " response_due_at<clock_timestamp()) AS overdue_responses,(SELECT count(*) FROM"
            + " support_cases WHERE status NOT IN('RESOLVED','CLOSED') AND"
            + " resolution_due_at<clock_timestamp()) AS overdue_resolutions,(SELECT count(*) FROM"
            + " listing_enforcements WHERE status='REVIEW_REQUESTED') AS listing_reviews,(SELECT"
            + " count(*) FROM security_mail_outbox WHERE status='PENDING') AS"
            + " pending_security_mail,(SELECT count(*) FROM security_mail_outbox WHERE"
            + " status='FAILED') AS failed_security_mail",
        (rs, n) -> {
          var m = new LinkedHashMap<String, Long>();
          for (String k :
              List.of(
                  "active_users",
                  "restricted_users",
                  "pending_account_actions",
                  "open_cases",
                  "escalated_cases",
                  "overdue_responses",
                  "overdue_resolutions",
                  "listing_reviews",
                  "pending_security_mail",
                  "failed_security_mail")) m.put(k, rs.getLong(k));
          return m;
        });
  }

  public List<Map<String, Object>> settings() {
    return jdbc.queryForList(
        "SELECT key,value,version,updated_at AS \"updatedAt\" FROM operational_settings ORDER BY"
            + " key");
  }

  public Map<String, Object> setting(String key, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT * FROM operational_settings WHERE key=?" + (lock ? " FOR UPDATE" : ""), key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public int settingValue(String key) {
    return jdbc.queryForObject(
        "SELECT value FROM operational_settings WHERE key=?", Integer.class, key);
  }

  public void setting(String key, int value, UUID actor, Instant now) {
    jdbc.update(
        "UPDATE operational_settings SET value=?,version=version+1,updated_by=?,updated_at=? WHERE"
            + " key=?",
        value,
        actor,
        Timestamp.from(now),
        key);
  }

  public Map<String, Object> listing(UUID id, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT"
                + " l.id,l.property_id,l.version,l.status,l.public_snapshot,p.owner_organization_id,p.manager_organization_id"
                + " FROM rental_listings l JOIN properties p ON p.id=l.property_id WHERE l.id=?"
                + (lock ? " FOR UPDATE OF p,l" : ""),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void lockOrganizations(UUID owner, UUID manager) {
    jdbc.queryForList(
        "SELECT id FROM organizations WHERE id=? OR id=? ORDER BY id FOR UPDATE", owner, manager);
  }

  public boolean related(UUID user, UUID owner, UUID manager) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM organization_memberships WHERE user_id=? AND"
                + " organization_id IN(?,?))",
            Boolean.class,
            user,
            owner,
            manager));
  }

  public Map<String, Object> review(UUID id, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT listing_id AS id,status,assigned_to AS \"assignedTo\",listing_version AS"
                + " \"listingVersion\",version,public_snapshot AS"
                + " \"publicSnapshot\",reason,created_at AS \"createdAt\",updated_at AS"
                + " \"updatedAt\" FROM listing_enforcements WHERE listing_id=?"
                + (lock ? " FOR UPDATE" : ""),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Map<String, Object>> reviews(String status, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT listing_id AS id,status,assigned_to AS \"assignedTo\",listing_version AS"
            + " \"listingVersion\",version,public_snapshot AS \"publicSnapshot\",reason,updated_at"
            + " AS \"updatedAt\" FROM listing_enforcements WHERE (?::text IS NULL OR status=?)"
            + " ORDER BY updated_at DESC,listing_id LIMIT ? OFFSET ?",
        status,
        status,
        limit,
        offset);
  }

  public void requestReview(UUID id, long version, Object snapshot, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " listing_enforcements(listing_id,status,listing_version,public_snapshot,reason,created_at,updated_at)"
            + " VALUES(?,'REVIEW_REQUESTED',?,?::jsonb,?,?,?) ON CONFLICT(listing_id) DO UPDATE SET"
            + " status='REVIEW_REQUESTED',assigned_to=NULL,listing_version=excluded.listing_version,public_snapshot=excluded.public_snapshot,reason=excluded.reason,version=listing_enforcements.version+1,updated_at=excluded.updated_at"
            + " WHERE listing_enforcements.status='APPROVED'",
        id,
        version,
        json.writeValueAsString(snapshot),
        reason,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void review(
      UUID id,
      String status,
      UUID assigned,
      long sourceVersion,
      String reason,
      Object snapshot,
      Instant now) {
    jdbc.update(
        "UPDATE listing_enforcements SET"
            + " status=?,assigned_to=?,listing_version=?,reason=?,public_snapshot=?::jsonb,version=version+1,updated_at=?"
            + " WHERE listing_id=?",
        status,
        assigned,
        sourceVersion,
        reason,
        json.writeValueAsString(snapshot),
        Timestamp.from(now),
        id);
  }

  public List<Map<String, Object>> operators(UUID exclude) {
    return jdbc.queryForList(
        "SELECT u.id,u.display_name AS \"displayName\" FROM app_users u JOIN account_mfa m ON"
            + " m.user_id=u.id AND m.enabled WHERE u.active AND u.email_verified_at IS NOT NULL AND"
            + " u.platform_role='PLATFORM_ADMIN' AND u.id<>? ORDER BY u.display_name,u.id LIMIT"
            + " 100",
        exclude);
  }

  public void lockSettings() {
    jdbc.queryForList("SELECT key FROM operational_settings ORDER BY key FOR UPDATE");
  }
}
