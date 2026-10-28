package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SupportRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  private static Instant time(ResultSet rs, String key) throws SQLException {
    return rs.getTimestamp(key) == null ? null : rs.getTimestamp(key).toInstant();
  }

  private static final RowMapper<SupportCaseResponse> CASE =
      (rs, n) ->
          new SupportCaseResponse(
              rs.getObject("id", UUID.class),
              rs.getObject("reporter_id", UUID.class),
              rs.getString("kind"),
              rs.getString("category"),
              rs.getString("title"),
              rs.getString("description"),
              rs.getObject("listing_id", UUID.class),
              rs.getString("status"),
              rs.getString("priority"),
              rs.getObject("assigned_to", UUID.class),
              rs.getLong("version"),
              time(rs, "response_due_at"),
              time(rs, "resolution_due_at"),
              time(rs, "first_response_at"),
              rs.getString("resolution"),
              time(rs, "resolved_at"),
              time(rs, "closed_at"),
              time(rs, "created_at"),
              time(rs, "updated_at"),
              false);

  public SupportRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public SupportCaseResponse get(UUID id, boolean lock) {
    return jdbc
        .query("SELECT * FROM support_cases WHERE id=?" + (lock ? " FOR UPDATE" : ""), CASE, id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<SupportCaseResponse> cases(
      UUID reporter, String status, boolean mine, UUID actor, boolean overdue, int l, int f) {
    return jdbc.query(
        "SELECT * FROM support_cases WHERE (?::uuid IS NULL OR reporter_id=?) AND (?::text IS NULL"
            + " OR status=?) AND (NOT ? OR assigned_to=?) AND (NOT ? OR (status NOT"
            + " IN('RESOLVED','CLOSED') AND resolution_due_at<clock_timestamp()) OR (status NOT"
            + " IN('WAITING_CUSTOMER','RESOLVED','CLOSED') AND first_response_at IS NULL AND"
            + " response_due_at<clock_timestamp())) ORDER BY CASE priority WHEN 'URGENT' THEN 0"
            + " WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END,created_at,id LIMIT ? OFFSET ?",
        CASE,
        reporter,
        reporter,
        status,
        status,
        mine,
        actor,
        overdue,
        l,
        f);
  }

  public UUID create(
      UUID reporter,
      SupportCaseRequest r,
      Object snapshot,
      int response,
      int resolution,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " support_cases(id,reporter_id,kind,category,title,description,listing_id,public_snapshot,status,priority,response_due_at,resolution_due_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?::jsonb,'NEW','NORMAL',?,?,?,?)",
        id,
        reporter,
        r.kind(),
        r.category(),
        r.title().trim(),
        r.description().trim(),
        r.listingId(),
        snapshot == null ? null : json.writeValueAsString(snapshot),
        Timestamp.from(now.plusSeconds(response * 3600L)),
        Timestamp.from(now.plusSeconds(resolution * 3600L)),
        Timestamp.from(now),
        Timestamp.from(now));
    return id;
  }

  public void state(
      UUID id,
      String status,
      UUID assigned,
      String priority,
      String resolution,
      Instant resolved,
      Instant closed,
      Instant now) {
    jdbc.update(
        "UPDATE support_cases SET"
            + " status=?,assigned_to=?,priority=?,resolution=?,resolved_at=?,closed_at=?,version=version+1,updated_at=?"
            + " WHERE id=?",
        status,
        assigned,
        priority,
        resolution,
        resolved == null ? null : Timestamp.from(resolved),
        closed == null ? null : Timestamp.from(closed),
        Timestamp.from(now),
        id);
  }

  public void touch(UUID id, boolean staff, Instant now) {
    jdbc.update(
        "UPDATE support_cases SET version=version+1,updated_at=?,first_response_at=CASE WHEN ? THEN"
            + " COALESCE(first_response_at,?) ELSE first_response_at END WHERE id=?",
        Timestamp.from(now),
        staff,
        Timestamp.from(now),
        id);
  }

  public void message(UUID id, UUID author, String body, boolean internal, Instant now) {
    jdbc.update(
        "INSERT INTO support_messages(id,case_id,author_id,body,internal,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        author,
        body,
        internal,
        Timestamp.from(now));
  }

  public List<Map<String, Object>> messages(UUID id, boolean internal, int l, int f) {
    return jdbc.queryForList(
        "SELECT id,case_id AS \"caseId\",author_id AS \"authorId\",body,internal,created_at AS"
            + " \"createdAt\" FROM support_messages WHERE case_id=? AND (? OR NOT internal) ORDER"
            + " BY created_at,id LIMIT ? OFFSET ?",
        id,
        internal,
        l,
        f);
  }

  public boolean operator(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM app_users u JOIN account_mfa m ON m.user_id=u.id AND"
                + " m.enabled WHERE u.id=? AND u.active AND u.platform_role='PLATFORM_ADMIN' AND"
                + " u.email_verified_at IS NOT NULL)",
            Boolean.class,
            id));
  }

  public void notify(UUID user, UUID id, String message, Instant now) {
    if (user != null)
      jdbc.update(
          "INSERT INTO support_notifications(id,user_id,case_id,message,created_at)"
              + " VALUES(?,?,?,?,?)",
          UUID.randomUUID(),
          user,
          id,
          message,
          Timestamp.from(now));
  }

  public List<Map<String, Object>> notifications(UUID user, int l, int f) {
    return jdbc.queryForList(
        "SELECT n.id,n.case_id AS \"caseId\",n.message,n.created_at AS \"createdAt\",n.read_at AS"
            + " \"readAt\",(c.reporter_id<>n.user_id) AS \"adminView\" FROM support_notifications n"
            + " JOIN support_cases c ON c.id=n.case_id WHERE n.user_id=? ORDER BY n.created_at"
            + " DESC,n.id LIMIT ? OFFSET ?",
        user,
        l,
        f);
  }

  public void read(UUID user, UUID id, Instant now) {
    jdbc.update(
        "UPDATE support_notifications SET read_at=COALESCE(read_at,?) WHERE id=? AND user_id=?",
        Timestamp.from(now),
        id,
        user);
  }

  public Map<String, Object> evidence(UUID caseId, UUID id) {
    return jdbc
        .queryForList("SELECT * FROM support_evidence WHERE case_id=? AND id=?", caseId, id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<SupportEvidenceResponse> evidence(UUID id, boolean internal, String base) {
    return jdbc.query(
        "SELECT * FROM support_evidence WHERE case_id=? AND (? OR NOT internal) ORDER BY"
            + " created_at,id",
        (rs, n) ->
            new SupportEvidenceResponse(
                rs.getObject("id", UUID.class),
                id,
                rs.getObject("author_id", UUID.class),
                rs.getString("caption"),
                rs.getBoolean("internal"),
                rs.getString("content_type"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getLong("byte_size"),
                rs.getString("sha256"),
                time(rs, "created_at"),
                base + "/" + id + "/evidence/" + rs.getObject("id", UUID.class) + "/content"),
        id,
        internal);
  }

  public int evidenceCount(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM support_evidence WHERE case_id=?", Integer.class, id);
  }

  public void evidence(
      UUID id,
      UUID caseId,
      UUID author,
      String caption,
      boolean internal,
      String key,
      String keyId,
      String prefix,
      com.zaza.housing.model.ProcessedImage image,
      long cipherSize,
      String hash,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " support_evidence(id,case_id,author_id,caption,internal,storage_key,key_id,envelope_prefix,content_type,width,height,byte_size,cipher_size,sha256,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        caseId,
        author,
        caption,
        internal,
        key,
        keyId,
        prefix,
        image.contentType(),
        image.width(),
        image.height(),
        image.bytes().length,
        cipherSize,
        hash,
        Timestamp.from(now));
  }
}
