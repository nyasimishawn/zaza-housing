package com.zaza.housing.repository;

import com.zaza.housing.dto.InspectionAttestationResponse;
import com.zaza.housing.dto.InspectionDetails;
import com.zaza.housing.dto.InspectionEventResponse;
import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.InspectionStatus;
import com.zaza.housing.model.InspectionPolicy;
import com.zaza.housing.model.InspectionRevision;
import com.zaza.housing.model.LeaseInspection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class InspectionRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private static final RowMapper<LeaseInspection> INSPECTION =
      (r, n) ->
          new LeaseInspection(
              r.getObject("id", UUID.class),
              r.getObject("lease_id", UUID.class),
              InspectionKind.valueOf(r.getString("kind")),
              InspectionStatus.valueOf(r.getString("status")),
              r.getLong("version"),
              r.getObject("current_revision_id", UUID.class),
              r.getObject("shared_revision_id", UUID.class),
              r.getObject("accepted_revision_id", UUID.class),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());

  public InspectionRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<InspectionPolicy> policy(UUID lease) {
    return jdbc
        .query(
            "SELECT * FROM lease_inspection_policies WHERE lease_id=?",
            (r, n) ->
                new InspectionPolicy(
                    r.getObject("lease_id", UUID.class),
                    r.getBoolean("move_in_required"),
                    r.getTimestamp("enabled_at").toInstant()),
            lease)
        .stream()
        .findFirst();
  }

  public void enable(UUID lease, UUID actor, boolean moveIn, Instant now) {
    jdbc.update(
        "INSERT INTO lease_inspection_policies(lease_id,enabled_by,move_in_required,enabled_at)"
            + " VALUES (?,?,?,?)",
        lease,
        actor,
        moveIn,
        java.sql.Timestamp.from(now));
  }

  public Optional<LeaseInspection> find(UUID lease, UUID id) {
    return jdbc
        .query("SELECT * FROM lease_inspections WHERE lease_id=? AND id=?", INSPECTION, lease, id)
        .stream()
        .findFirst();
  }

  public Optional<LeaseInspection> kind(UUID lease, InspectionKind kind) {
    return jdbc
        .query(
            "SELECT * FROM lease_inspections WHERE lease_id=? AND kind=?",
            INSPECTION,
            lease,
            kind.name())
        .stream()
        .findFirst();
  }

  public List<LeaseInspection> list(UUID lease, boolean tenant) {
    return jdbc.query(
        "SELECT * FROM lease_inspections WHERE lease_id=?"
            + (tenant ? " AND shared_revision_id IS NOT NULL" : "")
            + " ORDER BY kind,id",
        INSPECTION,
        lease);
  }

  public void create(UUID id, UUID lease, InspectionKind kind, UUID revision, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspections(id,lease_id,kind,status,current_revision_id,created_at,updated_at)"
            + " VALUES (?,?,?,'DRAFT',?,?,?)",
        id,
        lease,
        kind.name(),
        revision,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
  }

  public void revise(UUID id, UUID revision, Instant now) {
    jdbc.update(
        "UPDATE lease_inspections SET current_revision_id=?,version=version+1,updated_at=? WHERE"
            + " id=?",
        revision,
        java.sql.Timestamp.from(now),
        id);
  }

  public void touch(UUID id, Instant now) {
    jdbc.update(
        "UPDATE lease_inspections SET version=version+1,updated_at=? WHERE id=?",
        java.sql.Timestamp.from(now),
        id);
  }

  public void state(UUID id, InspectionStatus status, Instant now) {
    jdbc.update(
        "UPDATE lease_inspections SET status=?,shared_revision_id=CASE WHEN ?='AWAITING_TENANT'"
            + " THEN current_revision_id ELSE shared_revision_id END,accepted_revision_id=CASE WHEN"
            + " ?='ACCEPTED' THEN shared_revision_id ELSE accepted_revision_id"
            + " END,version=version+1,updated_at=? WHERE id=?",
        status.name(),
        status.name(),
        status.name(),
        java.sql.Timestamp.from(now),
        id);
  }

  public void revision(
      UUID id,
      UUID inspection,
      int number,
      InspectionDetails details,
      String currency,
      String document,
      String hash,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_revisions(id,inspection_id,number,details,currency,document,document_hash,created_at)"
            + " VALUES (?,?,?,?::jsonb,?,?,?,?)",
        id,
        inspection,
        number,
        json.writeValueAsString(details),
        currency,
        document,
        hash,
        java.sql.Timestamp.from(now));
  }

  private RowMapper<InspectionRevision> revisionMapper() {
    return (r, n) ->
        new InspectionRevision(
            r.getObject("id", UUID.class),
            r.getObject("inspection_id", UUID.class),
            r.getInt("number"),
            json.readValue(r.getString("details"), InspectionDetails.class),
            r.getString("currency"),
            r.getString("document"),
            r.getString("document_hash"),
            r.getTimestamp("created_at").toInstant(),
            r.getBoolean("shared"));
  }

  private static final String REVISIONS =
      "SELECT r.*,EXISTS(SELECT 1 FROM lease_inspection_shares s WHERE"
          + " s.inspection_id=r.inspection_id AND s.revision_id=r.id) shared FROM"
          + " lease_inspection_revisions r ";

  public Optional<InspectionRevision> revision(UUID inspection, UUID revision) {
    return jdbc
        .query(
            REVISIONS + "WHERE r.inspection_id=? AND r.id=?",
            revisionMapper(),
            inspection,
            revision)
        .stream()
        .findFirst();
  }

  public List<InspectionRevision> revisions(
      UUID inspection, boolean tenant, int limit, int offset) {
    return jdbc.query(
        REVISIONS
            + "WHERE r.inspection_id=?"
            + (tenant
                ? " AND EXISTS(SELECT 1 FROM lease_inspection_shares s WHERE"
                    + " s.inspection_id=r.inspection_id AND s.revision_id=r.id)"
                : "")
            + " ORDER BY number DESC LIMIT ? OFFSET ?",
        revisionMapper(),
        inspection,
        limit,
        offset);
  }

  public void share(
      LeaseInspection inspection,
      InspectionRevision revision,
      UUID actor,
      String reason,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_shares(inspection_id,revision_id,actor_id,document_hash,reason,method,created_at)"
            + " VALUES (?,?,?,?,?,'ACCOUNT_PASSWORD_CONSENT',?)",
        inspection.id(),
        revision.id(),
        actor,
        revision.documentHash(),
        reason,
        java.sql.Timestamp.from(now));
    for (UUID evidence : revision.details().evidenceIds())
      jdbc.update(
          "INSERT INTO"
              + " lease_inspection_evidence_publications(inspection_id,revision_id,evidence_id)"
              + " VALUES (?,?,?)",
          inspection.id(),
          revision.id(),
          evidence);
    state(inspection.id(), InspectionStatus.AWAITING_TENANT, now);
  }

  public void decision(
      LeaseInspection inspection,
      InspectionRevision revision,
      UUID actor,
      boolean accept,
      boolean vacancy,
      String reason,
      List<String> itemKeys,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_decisions(id,inspection_id,revision_id,actor_id,decision,document_hash,vacancy_confirmed,reason,item_keys,method,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        UUID.randomUUID(),
        inspection.id(),
        revision.id(),
        actor,
        accept ? "ACCEPT" : "DISPUTE",
        revision.documentHash(),
        vacancy,
        reason,
        json.writeValueAsString(itemKeys),
        accept ? "ACCOUNT_PASSWORD_CONSENT" : "ACCOUNT_REQUEST",
        java.sql.Timestamp.from(now));
    state(inspection.id(), accept ? InspectionStatus.ACCEPTED : InspectionStatus.DISPUTED, now);
  }

  public Optional<Map<String, Object>> operation(UUID lease, UUID request) {
    return jdbc
        .queryForList(
            "SELECT * FROM lease_inspection_operations WHERE lease_id=? AND request_id=?",
            lease,
            request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID lease,
      UUID request,
      UUID actor,
      String action,
      UUID target,
      String fingerprint,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_operations(lease_id,request_id,actor_id,action,target_id,fingerprint,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        lease,
        request,
        actor,
        action,
        target,
        fingerprint,
        java.sql.Timestamp.from(now));
  }

  public void event(
      UUID lease,
      UUID inspection,
      UUID revision,
      UUID actor,
      String party,
      String action,
      String reason,
      List<String> keys,
      boolean visible,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_events(id,lease_id,inspection_id,revision_id,actor_id,party,action,reason,item_keys,tenant_visible,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        UUID.randomUUID(),
        lease,
        inspection,
        revision,
        actor,
        party,
        action,
        reason,
        json.writeValueAsString(keys),
        visible,
        java.sql.Timestamp.from(now));
  }

  public List<InspectionEventResponse> history(
      UUID lease, UUID inspection, boolean tenant, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM lease_inspection_events WHERE lease_id=? AND inspection_id=?"
            + (tenant ? " AND tenant_visible" : "")
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        (r, n) ->
            new InspectionEventResponse(
                r.getObject("id", UUID.class),
                r.getString("action"),
                r.getString("party"),
                r.getObject("revision_id", UUID.class),
                r.getString("reason"),
                json.readValue(
                    r.getString("item_keys"),
                    json.getTypeFactory().constructCollectionType(List.class, String.class)),
                r.getTimestamp("created_at").toInstant()),
        lease,
        inspection,
        limit,
        offset);
  }

  public List<InspectionAttestationResponse> attestations(UUID inspection, int limit, int offset) {
    return jdbc.query(
        """
        SELECT revision_id,'LESSOR' party,'SHARE' decision,method,document_hash,
               EXISTS(SELECT 1 FROM lease_inspections i WHERE i.id=inspection_id AND i.kind='MOVE_OUT') vacancy_confirmed,created_at FROM lease_inspection_shares WHERE inspection_id=?
        UNION ALL
        SELECT revision_id,'TENANT' party,decision,method,document_hash,vacancy_confirmed,created_at
        FROM lease_inspection_decisions WHERE inspection_id=?
        ORDER BY created_at,revision_id,party LIMIT ? OFFSET ?
        """,
        (r, n) ->
            new InspectionAttestationResponse(
                r.getObject("revision_id", UUID.class),
                r.getString("party"),
                r.getString("decision"),
                r.getString("method"),
                r.getString("document_hash"),
                r.getBoolean("vacancy_confirmed"),
                r.getTimestamp("created_at").toInstant()),
        inspection,
        inspection,
        limit,
        offset);
  }
}
