package com.zaza.housing.repository;

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
  private static final RowMapper<VerificationCase> CASE =
      (r, n) ->
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
              r.getTimestamp("updated_at").toInstant());
  private static final String SUBMISSIONS =
      "SELECT s.*,EXISTS(SELECT 1 FROM verification_submission_shares v WHERE v.case_id=s.case_id"
          + " AND v.submission_id=s.id) submitted FROM verification_submissions s ";

  public VerificationRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<VerificationCase> find(UUID id) {
    return jdbc.query("SELECT * FROM verification_cases WHERE id=?", CASE, id).stream().findFirst();
  }

  public List<VerificationCase> list(UUID user, UUID org, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM verification_cases WHERE "
            + (org == null ? "owner_user_id=?" : "organization_id=?")
            + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        CASE,
        org == null ? user : org,
        limit,
        offset);
  }

  public Optional<VerificationCase> active(UUID user, UUID org) {
    return jdbc
        .query(
            "SELECT * FROM verification_cases WHERE "
                + (org == null ? "owner_user_id=?" : "organization_id=?")
                + " AND status IN"
                + " ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED','VERIFIED')",
            CASE,
            org == null ? user : org)
        .stream()
        .findFirst();
  }

  public void create(UUID id, UUID user, UUID org, UUID submission, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " verification_cases(id,owner_user_id,organization_id,kind,status,current_submission_id,created_by,created_at,updated_at)"
            + " VALUES (?,?,?,?,'DRAFT',?,?,?,?)",
        id,
        user,
        org,
        org == null ? "IDENTITY" : "BUSINESS",
        submission,
        actor,
        Timestamp.from(now),
        Timestamp.from(now));
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
             OR EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=c.organization_id AND m.user_id=?)))
            """,
            Boolean.class,
            verification,
            actor,
            actor,
            actor,
            actor));
  }

  public boolean currentBusinessName(VerificationCase c) {
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
        CASE,
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
