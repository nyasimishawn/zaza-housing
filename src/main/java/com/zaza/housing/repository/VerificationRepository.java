package com.zaza.housing.repository;

import com.zaza.housing.dto.PropertyOwnershipSubject;
import com.zaza.housing.dto.VerificationDetails;
import com.zaza.housing.enums.VerificationKind;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.VerificationCase;
import com.zaza.housing.model.VerificationSubmission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class VerificationRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  private RowMapper<VerificationCase> caseMapper() {
    return (r, n) ->
        new VerificationCase(
            r.getObject("id", UUID.class),
            r.getObject("owner_user_id", UUID.class),
            r.getObject("organization_id", UUID.class),
            VerificationKind.valueOf(r.getString("kind")),
            VerificationStatus.valueOf(r.getString("status")),
            r.getLong("version"),
            r.getObject("current_submission_id", UUID.class),
            r.getObject("shared_submission_id", UUID.class),
            r.getObject("assigned_to", UUID.class),
            r.getTimestamp("valid_until") == null
                ? null
                : r.getTimestamp("valid_until").toInstant(),
            r.getString("resolution"),
            r.getObject("created_by", UUID.class),
            r.getTimestamp("submitted_at") == null
                ? null
                : r.getTimestamp("submitted_at").toInstant(),
            r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at").toInstant(),
            r.getObject("property_id", UUID.class),
            r.getString("ownership_subject") == null
                ? null
                : json.readValue(r.getString("ownership_subject"), PropertyOwnershipSubject.class));
  }

  private static final String SUBMISSIONS =
      "SELECT s.*,EXISTS(SELECT 1 FROM verification_submission_shares v WHERE v.case_id=s.case_id"
          + " AND v.submission_id=s.id) submitted FROM verification_submissions s ";

  public VerificationRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<VerificationCase> find(UUID id) {
    return jdbc.query("SELECT * FROM verification_cases WHERE id=?", caseMapper(), id).stream()
        .findFirst();
  }

  public List<VerificationCase> list(UUID user, UUID org, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM verification_cases WHERE "
            + (org == null ? "owner_user_id=?" : "organization_id=?")
            + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        caseMapper(),
        org == null ? user : org,
        limit,
        offset);
  }

  public Optional<VerificationCase> active(UUID user, UUID org) {
    return jdbc
        .query(
            "SELECT * FROM verification_cases WHERE "
                + (org == null ? "owner_user_id=?" : "organization_id=?")
                + " AND property_id IS NULL AND status IN"
                + " ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED')",
            caseMapper(),
            org == null ? user : org)
        .stream()
        .findFirst();
  }

  public void create(UUID id, UUID user, UUID org, UUID submission, UUID actor, Instant now) {
    create(id, user, org, submission, actor, now, null);
  }

  public void create(
      UUID id,
      UUID user,
      UUID org,
      UUID submission,
      UUID actor,
      Instant now,
      PropertyOwnershipSubject property) {
    jdbc.update(
        "INSERT INTO"
            + " verification_cases(id,owner_user_id,organization_id,kind,status,current_submission_id,created_by,created_at,updated_at,property_id,ownership_subject)"
            + " VALUES (?,?,?,?,'DRAFT',?,?,?,?,?,?::jsonb)",
        id,
        user,
        org,
        property != null ? "PROPERTY_OWNERSHIP" : org == null ? "IDENTITY" : "BUSINESS",
        submission,
        actor,
        Timestamp.from(now),
        Timestamp.from(now),
        property == null ? null : property.propertyId(),
        property == null ? null : json.writeValueAsString(property));
  }

  public Optional<VerificationCase> activeProperty(UUID org, UUID property) {
    return jdbc
        .query(
            "SELECT * FROM verification_cases WHERE organization_id=? AND property_id=? AND status"
                + " IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED')",
            caseMapper(),
            org,
            property)
        .stream()
        .findFirst();
  }

  public UUID propertyOwner(UUID property) {
    return jdbc.queryForObject(
        "SELECT owner_organization_id FROM properties WHERE id=?", UUID.class, property);
  }

  public List<VerificationCase> propertyCases(UUID org, UUID property, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM verification_cases WHERE organization_id=? AND property_id=? ORDER BY"
            + " created_at DESC,id LIMIT ? OFFSET ?",
        caseMapper(),
        org,
        property,
        limit,
        offset);
  }

  public Optional<PropertyOwnershipSubject> propertySubject(UUID org, UUID property, boolean lock) {
    return jdbc
        .query(
            "SELECT p.*,o.name organization_name FROM properties p JOIN organizations o ON"
                + " o.id=p.owner_organization_id WHERE p.id=? AND p.owner_organization_id=?"
                + (lock ? " FOR UPDATE OF p" : ""),
            (r, n) ->
                new PropertyOwnershipSubject(
                    r.getObject("id", UUID.class),
                    r.getObject("owner_organization_id", UUID.class),
                    r.getString("organization_name"),
                    r.getString("name"),
                    r.getString("address"),
                    r.getString("city"),
                    r.getString("country_code")),
            property,
            org)
        .stream()
        .findFirst();
  }

  public void submission(
      UUID id,
      UUID verification,
      int number,
      VerificationDetails details,
      UUID actor,
      Instant now) {
    jdbc.update(
        "INSERT INTO verification_submissions(id,case_id,number,details,prepared_by,created_at)"
            + " VALUES (?,?,?,?::jsonb,?,?)",
        id,
        verification,
        number,
        json.writeValueAsString(details),
        actor,
        Timestamp.from(now));
  }

  public void evidence(
      UUID verification, UUID submission, UUID document, UUID version, int position) {
    jdbc.update(
        "INSERT INTO"
            + " verification_submission_documents(case_id,submission_id,document_id,document_version_id,position)"
            + " VALUES (?,?,?,?,?)",
        verification,
        submission,
        document,
        version,
        position);
  }

  private RowMapper<VerificationSubmission> submissionMapper() {
    return (r, n) ->
        new VerificationSubmission(
            r.getObject("id", UUID.class),
            r.getObject("case_id", UUID.class),
            r.getInt("number"),
            json.readValue(r.getString("details"), VerificationDetails.class),
            r.getObject("prepared_by", UUID.class),
            r.getBoolean("submitted"),
            r.getTimestamp("created_at").toInstant());
  }

  public VerificationSubmission submission(UUID verification, UUID id) {
    return jdbc
        .query(SUBMISSIONS + " WHERE s.case_id=? AND s.id=?", submissionMapper(), verification, id)
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public List<VerificationSubmission> submissions(UUID verification, int limit, int offset) {
    return jdbc.query(
        SUBMISSIONS + " WHERE s.case_id=? ORDER BY s.number DESC LIMIT ? OFFSET ?",
        submissionMapper(),
        verification,
        limit,
        offset);
  }

  public void current(UUID verification, UUID submission, Instant now) {
    jdbc.update(
        "UPDATE verification_cases SET current_submission_id=?,version=version+1,updated_at=? WHERE"
            + " id=?",
        submission,
        Timestamp.from(now),
        verification);
  }

  public void submit(UUID verification, UUID submission, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " verification_submission_shares(case_id,submission_id,actor_id,method,created_at)"
            + " VALUES (?,?,?,'ACCOUNT_PASSWORD_CONSENT',?)",
        verification,
        submission,
        actor,
        Timestamp.from(now));
    jdbc.update(
        "UPDATE verification_cases SET"
            + " status='SUBMITTED',shared_submission_id=?,assigned_to=NULL,resolution=NULL,version=version+1,submitted_at=?,updated_at=?"
            + " WHERE id=?",
        submission,
        Timestamp.from(now),
        Timestamp.from(now),
        verification);
  }

  public void claim(UUID verification, UUID actor, Instant now) {
    jdbc.update(
        "UPDATE verification_cases SET"
            + " status='UNDER_REVIEW',assigned_to=?,version=version+1,updated_at=? WHERE id=?",
        actor,
        Timestamp.from(now),
        verification);
  }

  public void resolve(
      UUID verification,
      VerificationStatus status,
      String reason,
      Instant validUntil,
      Instant now) {
    jdbc.update(
        "UPDATE verification_cases SET"
            + " status=?,assigned_to=NULL,resolution=?,valid_until=COALESCE(?,valid_until),version=version+1,updated_at=?"
            + " WHERE id=?",
        status.name(),
        reason,
        validUntil == null ? null : Timestamp.from(validUntil),
        Timestamp.from(now),
        verification);
  }

  public boolean related(UUID actor, UUID verification) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS(SELECT 1 FROM verification_cases c WHERE c.id=? AND
            (c.owner_user_id=? OR c.created_by=? OR EXISTS(SELECT 1 FROM verification_submissions s WHERE s.case_id=c.id AND s.prepared_by=?)
             OR EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=c.organization_id AND m.user_id=?)
             OR EXISTS(SELECT 1 FROM properties p JOIN organization_memberships m ON m.organization_id=p.manager_organization_id WHERE p.id=c.property_id AND m.user_id=?)))
            """,
            Boolean.class,
            verification,
            actor,
            actor,
            actor,
            actor,
            actor));
  }

  public boolean currentBusinessName(VerificationCase c) {
    if (c.propertyId() != null
        && !propertySubject(c.organizationId(), c.propertyId(), false)
            .map(c.property()::equals)
            .orElse(false)) return false;
    if (c.organizationId() == null) return true;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM organizations o JOIN verification_submissions s ON"
                + " s.case_id=? AND s.id=? WHERE o.id=? AND"
                + " lower(btrim(o.name))=lower(btrim(s.details->>'claimedName')))",
            Boolean.class,
            c.id(),
            c.sharedSubmissionId() == null ? c.currentSubmissionId() : c.sharedSubmissionId(),
            c.organizationId()));
  }

  public String organizationName(UUID org) {
    return jdbc.queryForObject("SELECT name FROM organizations WHERE id=?", String.class, org);
  }

  public List<VerificationCase> queue(
      VerificationStatus status, boolean mine, UUID actor, Instant now, int limit, int offset) {
    var args = new ArrayList<Object>();
    String filter = "";
    if (status == VerificationStatus.EXPIRED) {
      filter = " AND status='VERIFIED' AND valid_until<=?";
      args.add(Timestamp.from(now));
    } else if (status != null) {
      filter = " AND status=?";
      args.add(status.name());
      if (status == VerificationStatus.VERIFIED) {
        filter += " AND valid_until>?";
        args.add(Timestamp.from(now));
      }
    }
    if (mine) {
      filter += " AND assigned_to=?";
      args.add(actor);
    }
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM verification_cases WHERE submitted_at IS NOT NULL"
            + filter
            + " ORDER BY updated_at,id LIMIT ? OFFSET ?",
        caseMapper(),
        args.toArray());
  }

  public boolean evidenceContains(UUID verification, UUID submission, UUID version) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM verification_submission_documents WHERE case_id=? AND"
                + " submission_id=? AND document_version_id=?)",
            Boolean.class,
            verification,
            submission,
            version));
  }
}
