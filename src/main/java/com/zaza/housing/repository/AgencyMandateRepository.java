package com.zaza.housing.repository;

import com.zaza.housing.dto.AgencyMandateRequest;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.model.AgencyMandate;
import java.sql.Timestamp;
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
public class AgencyMandateRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AgencyMandateRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static Timestamp t(Instant v) {
    return v == null ? null : Timestamp.from(v);
  }

  private RowMapper<AgencyMandate> mapper() {
    return (r, n) ->
        new AgencyMandate(
            r.getObject("id", UUID.class),
            r.getObject("owner_id", UUID.class),
            r.getString("owner_name"),
            r.getObject("agency_id", UUID.class),
            r.getString("agency_name"),
            r.getObject("property_id", UUID.class),
            r.getLong("property_version"),
            r.getString("property_name"),
            json.readValue(r.getString("terms"), Map.class),
            r.getTimestamp("starts_at").toInstant(),
            r.getTimestamp("ends_at").toInstant(),
            r.getString("status"),
            r.getBoolean("effective"),
            r.getLong("version"),
            r.getObject("requested_by", UUID.class),
            r.getObject("accepted_by", UUID.class),
            r.getTimestamp("accepted_at") == null
                ? null
                : r.getTimestamp("accepted_at").toInstant(),
            r.getTimestamp("closed_at") == null ? null : r.getTimestamp("closed_at").toInstant(),
            r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at").toInstant());
  }

  private static final String SELECT =
      "SELECT m.*,o.name owner_name,a.name agency_name,(m.status='ACTIVE' AND m.starts_at<=statement_timestamp()"
          + " AND m.ends_at>statement_timestamp() AND EXISTS(SELECT 1 FROM properties p WHERE p.id=m.property_id"
          + " AND p.management_mandate_id=m.id AND p.manager_organization_id=m.agency_id))"
          + " effective FROM agency_mandates m JOIN organizations o ON o.id=m.owner_id JOIN"
          + " organizations a ON a.id=m.agency_id";

  public Optional<AgencyMandate> get(UUID org, UUID id, boolean lock) {
    return jdbc
        .query(
            SELECT
                + " WHERE (m.owner_id=? OR m.agency_id=?) AND m.id=?"
                + (lock ? " FOR UPDATE OF m" : ""),
            mapper(),
            org,
            org,
            id)
        .stream()
        .findFirst();
  }

  public List<AgencyMandate> list(UUID org, int limit, int offset) {
    return jdbc.query(
        SELECT
            + " WHERE m.owner_id=? OR m.agency_id=? ORDER BY m.created_at DESC,m.id LIMIT ? OFFSET"
            + " ?",
        mapper(),
        org,
        org,
        limit,
        offset);
  }

  public List<Map<String, Object>> agencies(UUID org, String q, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT id,name FROM organizations WHERE type='AGENCY' AND id<>? AND position(lower(?) IN"
            + " lower(name))>0 ORDER BY name,id LIMIT ? OFFSET ?",
        org,
        q,
        limit,
        offset);
  }

  public List<AgencyPropertyResponse> properties(UUID org, int limit, int offset) {
    return jdbc.query(
        "SELECT id,name,city,country_code,status,version FROM properties WHERE"
            + " owner_organization_id=? AND status='ACTIVE' ORDER BY name,id LIMIT ? OFFSET ?",
        (r, n) ->
            new AgencyPropertyResponse(
                r.getObject("id", UUID.class),
                r.getString("name"),
                r.getString("city"),
                r.getString("country_code"),
                r.getString("status"),
                r.getLong("version")),
        org,
        limit,
        offset);
  }

  public UUID create(
      UUID org,
      UUID actor,
      com.zaza.housing.model.Property property,
      AgencyMandateRequest r,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " agency_mandates(id,owner_id,agency_id,property_id,property_version,property_name,terms,starts_at,ends_at,status,requested_by,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,'SENT',?,?,?)",
        id,
        org,
        r.agencyId(),
        r.propertyId(),
        r.propertyVersion(),
        property.name(),
        json.writeValueAsString(
            Map.of(
                "propertyAddress",
                property.address(),
                "city",
                property.city(),
                "countryCode",
                property.countryCode(),
                "responsibilities",
                r.responsibilities().strip(),
                "feeTerms",
                r.feeTerms().strip())),
        t(r.startsAt()),
        t(r.endsAt()),
        actor,
        t(now),
        t(now));
    return id;
  }

  public boolean isAgency(UUID org) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM organizations WHERE id=? AND type='AGENCY')",
            Boolean.class,
            org));
  }

  public boolean replaceable(UUID property) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT manager_organization_id IS NULL OR (management_mandate_id IS NOT NULL AND"
                + " EXISTS(SELECT 1 FROM agency_mandates m WHERE m.id=management_mandate_id AND"
                + " (m.status<>'ACTIVE' OR m.ends_at<=statement_timestamp()))) FROM properties WHERE id=?",
            Boolean.class,
            property));
  }

  public void state(UUID id, String status, UUID accepter, Instant now) {
    jdbc.update(
        "UPDATE agency_mandates SET"
            + " status=?,version=version+1,accepted_by=COALESCE(?,accepted_by),accepted_at=CASE"
            + " WHEN ?='ACTIVE' THEN ? ELSE accepted_at END,closed_at=CASE WHEN ?<>'ACTIVE' THEN ?"
            + " ELSE closed_at END,updated_at=? WHERE id=?",
        status,
        accepter,
        status,
        t(now),
        status,
        t(now),
        t(now),
        id);
  }

  public void delegate(AgencyMandate m) {
    jdbc.update(
        "UPDATE properties SET"
            + " manager_organization_id=?,management_mandate_id=?,version=version+1,updated_at=clock_timestamp()"
            + " WHERE id=?",
        m.agencyId(),
        m.id(),
        m.propertyId());
  }

  public void revoke(AgencyMandate m) {
    jdbc.update(
        "UPDATE properties SET"
            + " manager_organization_id=NULL,management_mandate_id=NULL,version=version+1,updated_at=clock_timestamp()"
            + " WHERE id=? AND management_mandate_id=?",
        m.propertyId(),
        m.id());
  }
}
