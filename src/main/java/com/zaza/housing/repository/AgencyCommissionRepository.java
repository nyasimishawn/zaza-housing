package com.zaza.housing.repository;

import com.zaza.housing.dto.AgencyCommissionRequest;
import com.zaza.housing.model.AgencyCommission;
import com.zaza.housing.model.AgencyLead;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AgencyCommissionRepository {
  private final JdbcTemplate jdbc;

  public AgencyCommissionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp t(Instant v) {
    // PostgreSQL stores microseconds; truncate instead of rounding an approval into the future.
    return v == null ? null : Timestamp.from(v.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  private static Instant instant(java.sql.ResultSet r, String key) throws java.sql.SQLException {
    var v = r.getTimestamp(key);
    return v == null ? null : v.toInstant();
  }

  private final RowMapper<AgencyCommission> mapper =
      (r, n) ->
          new AgencyCommission(
              r.getObject("id", UUID.class),
              r.getObject("agency_id", UUID.class),
              r.getObject("lead_id", UUID.class),
              r.getObject("lease_id", UUID.class),
              r.getObject("agent_id", UUID.class),
              r.getString("lead_title"),
              r.getString("agent_name"),
              r.getBigDecimal("amount"),
              r.getString("currency"),
              r.getString("agreement"),
              r.getString("status"),
              r.getLong("version"),
              r.getObject("created_by", UUID.class),
              r.getObject("approved_by", UUID.class),
              instant(r, "approved_at"),
              r.getObject("paid_by", UUID.class),
              instant(r, "paid_at"),
              r.getString("external_reference"),
              instant(r, "created_at"),
              instant(r, "updated_at"));

  public Optional<AgencyCommission> get(
      UUID org, UUID actor, boolean manager, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM agency_commissions WHERE agency_id=? AND id=? AND (? OR agent_id=?)"
                + (lock ? " FOR UPDATE" : ""),
            mapper,
            org,
            id,
            manager,
            actor)
        .stream()
        .findFirst();
  }

  public List<AgencyCommission> list(
      UUID org, UUID actor, boolean manager, String status, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM agency_commissions WHERE agency_id=? AND (? OR agent_id=?) AND (CAST(? AS"
            + " text) IS NULL OR status=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        mapper,
        org,
        manager,
        actor,
        status,
        status,
        limit,
        offset);
  }

  public Optional<String> currency(UUID lease) {
    return jdbc
        .query(
            "SELECT r.terms->>'currency' FROM rental_leases l JOIN lease_revisions r ON"
                + " r.id=l.shared_revision_id WHERE l.id=? AND l.status"
                + " IN('SIGNED','ACTIVE','COMPLETED','TERMINATED')",
            (r, n) -> r.getString(1),
            lease)
        .stream()
        .findFirst();
  }

  public UUID create(UUID org, UUID actor, AgencyLead l, AgencyCommissionRequest r, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " agency_commissions(id,agency_id,lead_id,lease_id,agent_id,lead_title,agent_name,amount,currency,agreement,status,created_by,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,(SELECT display_name FROM app_users WHERE"
            + " id=?),?,?,?,'SUBMITTED',?,?,?)",
        id,
        org,
        l.id(),
        l.leaseId(),
        r.agentId(),
        l.title(),
        r.agentId(),
        r.amount(),
        r.currency(),
        r.agreement().strip(),
        actor,
        t(now),
        t(now));
    return id;
  }

  public void state(
      UUID id, String status, UUID actor, String reference, Instant paid, Instant now) {
    jdbc.update(
        "UPDATE agency_commissions SET status=?,version=version+1,approved_by=CASE WHEN"
            + " ?='APPROVED' THEN ? ELSE approved_by END,approved_at=CASE WHEN ?='APPROVED' THEN ?"
            + " ELSE approved_at END,paid_by=CASE WHEN ?='PAID' THEN ? ELSE paid_by"
            + " END,paid_at=COALESCE(?,paid_at),external_reference=COALESCE(?,external_reference),updated_at=?"
            + " WHERE id=?",
        status,
        status,
        actor,
        status,
        t(now),
        status,
        actor,
        t(paid),
        reference,
        t(now),
        id);
  }
}
