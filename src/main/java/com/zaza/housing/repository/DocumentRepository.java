package com.zaza.housing.repository;

import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.enums.DocumentCategory;
import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.model.DocumentGrant;
import com.zaza.housing.model.DocumentReviewer;
import com.zaza.housing.model.ProcessedImage;
import com.zaza.housing.model.VaultDocument;
import com.zaza.housing.model.VaultDocumentVersion;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<VaultDocument> DOCUMENT =
      (r, n) ->
          new VaultDocument(
              r.getObject("id", UUID.class),
              r.getObject("owner_user_id", UUID.class),
              r.getObject("organization_id", UUID.class),
              DocumentCategory.valueOf(r.getString("category")),
              r.getString("title"),
              DocumentStatus.valueOf(r.getString("status")),
              r.getLong("version"),
              r.getObject("current_version_id", UUID.class),
              r.getTimestamp("retain_until").toInstant(),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());
  private static final RowMapper<VaultDocumentVersion> VERSION =
      (r, n) ->
          new VaultDocumentVersion(
              r.getObject("id", UUID.class),
              r.getObject("document_id", UUID.class),
              r.getInt("number"),
              r.getString("storage_key"),
              r.getString("key_id"),
              r.getString("content_type"),
              r.getString("content_hash"),
              r.getInt("width"),
              r.getInt("height"),
              r.getLong("byte_size"),
              r.getLong("cipher_size"),
              r.getTimestamp("created_at").toInstant());
  private static final RowMapper<DocumentGrant> GRANT =
      (r, n) ->
          new DocumentGrant(
              r.getObject("id", UUID.class),
              r.getObject("document_id", UUID.class),
              r.getObject("document_version_id", UUID.class),
              r.getObject("application_id", UUID.class),
              r.getLong("version"),
              r.getTimestamp("expires_at").toInstant(),
              r.getTimestamp("revoked_at") == null
                  ? null
                  : r.getTimestamp("revoked_at").toInstant(),
              r.getTimestamp("created_at").toInstant());

  public DocumentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockPersonal(UUID user) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", r -> {}, "vault-user:" + user);
  }

  public Optional<VaultDocument> find(UUID id) {
    return jdbc.query("SELECT * FROM vault_documents WHERE id=?", DOCUMENT, id).stream()
        .findFirst();
  }

  public Optional<VaultDocument> forVersion(UUID id) {
    return jdbc
        .query(
            "SELECT d.* FROM vault_documents d JOIN vault_document_versions v ON v.document_id=d.id"
                + " WHERE v.id=?",
            DOCUMENT,
            id)
        .stream()
        .findFirst();
  }

  public List<VaultDocument> list(
      UUID user, UUID org, DocumentStatus status, int limit, int offset) {
    String scope = org == null ? "owner_user_id=?" : "organization_id=?";
    var args = new ArrayList<Object>();
    args.add(org == null ? user : org);
    if (status != null) args.add(status.name());
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM vault_documents WHERE "
            + scope
            + (status == null ? "" : " AND status=?")
            + " ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
        DOCUMENT,
        args.toArray());
  }

  public int activeCount(UUID user, UUID org) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM vault_documents WHERE "
            + (org == null ? "owner_user_id=?" : "organization_id=?")
            + " AND status<>'PURGED'",
        Integer.class,
        org == null ? user : org);
  }

  public void create(
      UUID id,
      UUID user,
      UUID org,
      DocumentCategory category,
      String title,
      UUID version,
      Instant retainUntil,
      UUID actor,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " vault_documents(id,owner_user_id,organization_id,category,title,status,current_version_id,retain_until,created_by,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,'ACTIVE',?,?,?,?,?)",
        id,
        user,
        org,
        category.name(),
        title,
        version,
        Timestamp.from(retainUntil),
        actor,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void version(
      UUID id,
      UUID document,
      int number,
      String key,
      String keyId,
      ProcessedImage image,
      String hash,
      long cipherSize,
      UUID actor,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " vault_document_versions(id,document_id,number,storage_key,key_id,content_type,content_hash,width,height,byte_size,cipher_size,created_by,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        document,
        number,
        key,
        keyId,
        image.contentType(),
        hash,
        image.width(),
        image.height(),
        image.bytes().length,
        cipherSize,
        actor,
        Timestamp.from(now));
  }

  public Optional<VaultDocumentVersion> version(UUID document, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM vault_document_versions WHERE document_id=? AND id=?",
            VERSION,
            document,
            id)
        .stream()
        .findFirst();
  }

  public List<VaultDocumentVersion> versions(UUID document, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM vault_document_versions WHERE document_id=? ORDER BY number DESC LIMIT ?"
            + " OFFSET ?",
        VERSION,
        document,
        limit,
        offset);
  }

  public void current(UUID document, UUID version, Instant now) {
    jdbc.update(
        "UPDATE vault_documents SET current_version_id=?,version=version+1,updated_at=? WHERE id=?",
        version,
        Timestamp.from(now),
        document);
  }

  public void state(UUID document, DocumentStatus status, Instant now) {
    jdbc.update(
        "UPDATE vault_documents SET status=?,version=version+1,updated_at=? WHERE id=?",
        status.name(),
        Timestamp.from(now),
        document);
  }

  public void retention(UUID document, Instant until, Instant now) {
    jdbc.update(
        "UPDATE vault_documents SET retain_until=?,version=version+1,updated_at=? WHERE id=?",
        Timestamp.from(until),
        Timestamp.from(now),
        document);
  }

  public boolean held(UUID document, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS(SELECT 1 FROM verification_submission_documents d JOIN verification_cases c ON c.id=d.case_id
            WHERE d.document_id=? AND
            ((c.status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED') AND (d.submission_id=c.current_submission_id OR d.submission_id=c.shared_submission_id))
            OR (c.status='VERIFIED' AND c.valid_until>? AND d.submission_id=c.shared_submission_id)))
            """,
            Boolean.class,
            document,
            Timestamp.from(now)));
  }

  public Optional<Map<String, Object>> operation(String scope, UUID request) {
    return jdbc
        .queryForList(
            "SELECT * FROM vault_operations WHERE scope_key=? AND request_id=?", scope, request)
        .stream()
        .findFirst();
  }

  public void operation(
      String scope,
      UUID request,
      UUID actor,
      String action,
      UUID target,
      String fingerprint,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " vault_operations(scope_key,request_id,actor_id,action,target_id,fingerprint,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        scope,
        request,
        actor,
        action,
        target,
        fingerprint,
        Timestamp.from(now));
  }

  public int uploads(String scope, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM vault_operations WHERE scope_key=? AND created_at>? AND action IN"
            + " ('DOCUMENT_UPLOADED','DOCUMENT_VERSION_ADDED')",
        Integer.class,
        scope,
        Timestamp.from(since));
  }

  public UUID event(
      UUID document,
      UUID verification,
      UUID actor,
      String party,
      String action,
      String reason,
      boolean visible,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " vault_events(id,document_id,case_id,action,actor_id,party,reason,reviewer_visible,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        document,
        verification,
        action,
        actor,
        party,
        reason,
        visible,
        Timestamp.from(now));
    return id;
  }

  public List<DocumentEventResponse> history(
      UUID document, UUID verification, boolean reviewer, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM vault_events WHERE "
            + (document != null ? "document_id=?" : "case_id=?")
            + (reviewer ? " AND reviewer_visible" : "")
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        (r, n) ->
            new DocumentEventResponse(
                r.getObject("id", UUID.class),
                r.getString("action"),
                r.getString("party"),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant()),
        document != null ? document : verification,
        limit,
        offset);
  }

  public List<UUID> recipients(UUID user, UUID org) {
    if (org == null) return List.of(user);
    return jdbc.query(
        "SELECT m.user_id FROM organization_memberships m JOIN app_users u ON u.id=m.user_id AND"
            + " u.active WHERE m.organization_id=? AND m.role IN ('OWNER','ADMIN')",
        (r, n) -> r.getObject("user_id", UUID.class),
        org);
  }

  public UUID grant(
      UUID doc, UUID version, UUID application, Instant expires, UUID actor, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " vault_document_grants(id,document_id,document_version_id,application_id,purpose,expires_at,created_by,created_at)"
            + " VALUES (?,?,?,?,'APPLICATION_SCREENING',?,?,?)",
        id,
        doc,
        version,
        application,
        Timestamp.from(expires),
        actor,
        Timestamp.from(now));
    return id;
  }

  public Optional<DocumentGrant> grant(UUID application, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM vault_document_grants WHERE application_id=? AND id=?",
            GRANT,
            application,
            id)
        .stream()
        .findFirst();
  }

  public List<DocumentGrant> grants(UUID application, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM vault_document_grants WHERE application_id=? ORDER BY created_at,id LIMIT ?"
            + " OFFSET ?",
        GRANT,
        application,
        limit,
        offset);
  }

  public int grantCount(UUID application) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM vault_document_grants WHERE application_id=?",
        Integer.class,
        application);
  }

  public void revoke(UUID id, Instant now) {
    jdbc.update(
        "UPDATE vault_document_grants SET revoked_at=?,version=version+1 WHERE id=?",
        Timestamp.from(now),
        id);
  }

  public void queuePurge(UUID document) {
    jdbc.update(
        "INSERT INTO vault_purge_tasks(document_id,document_version_id) SELECT document_id,id FROM"
            + " vault_document_versions WHERE document_id=?",
        document);
  }

  public List<VaultDocumentVersion> purgeTasks(UUID document) {
    return jdbc.query(
        "SELECT v.* FROM vault_document_versions v JOIN vault_purge_tasks p ON"
            + " p.document_id=v.document_id AND p.document_version_id=v.id WHERE p.document_id=?"
            + " AND p.completed_at IS NULL ORDER BY v.number",
        VERSION,
        document);
  }

  public void purged(UUID document, UUID version, Instant now) {
    jdbc.update(
        "UPDATE vault_purge_tasks SET completed_at=? WHERE document_id=? AND document_version_id=?"
            + " AND completed_at IS NULL",
        Timestamp.from(now),
        document,
        version);
  }

  public Map<UUID, VaultDocumentVersion> currentVersions(List<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    var result = new HashMap<UUID, VaultDocumentVersion>();
    jdbc.query(
            "SELECT * FROM vault_document_versions WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ")",
            VERSION,
            ids.toArray())
        .forEach(v -> result.put(v.id(), v));
    return Map.copyOf(result);
  }

  public void revokeAll(UUID document, Instant now) {
    jdbc.update(
        "UPDATE vault_document_grants SET revoked_at=?,version=version+1 WHERE document_id=? AND"
            + " revoked_at IS NULL",
        Timestamp.from(now),
        document);
  }

  public Optional<DocumentGrant> activeGrant(UUID application, UUID version) {
    return jdbc
        .query(
            "SELECT * FROM vault_document_grants WHERE application_id=? AND document_version_id=?"
                + " AND revoked_at IS NULL",
            GRANT,
            application,
            version)
        .stream()
        .findFirst();
  }

  public List<DocumentReviewer> applicationReviewers(UUID property) {
    return jdbc.query(
        """
        SELECT DISTINCT ON(m.user_id) m.user_id,m.organization_id FROM properties p
        JOIN organization_memberships m ON agency_management_access(m.organization_id,p.id)
        JOIN app_users u ON u.id=m.user_id AND u.active WHERE p.id=? AND m.role IN ('OWNER','ADMIN')
        ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
        """,
        (r, n) ->
            new DocumentReviewer(
                r.getObject("user_id", UUID.class), r.getObject("organization_id", UUID.class)),
        property);
  }
}
