package com.zaza.housing.repository;

import com.zaza.housing.dto.CaretakerOccupancyResponse;
import com.zaza.housing.dto.PropertyNoticeCreateRequest;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyNotice;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyNoticeRepository {
  private final JdbcTemplate jdbc;

  public PropertyNoticeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static java.time.Instant instant(java.sql.ResultSet r, String k)
      throws java.sql.SQLException {
    return r.getTimestamp(k) == null ? null : r.getTimestamp(k).toInstant();
  }

  private static Timestamp time(Instant t) {
    return t == null ? null : Timestamp.from(t);
  }

  private final RowMapper<PropertyNotice> mapper =
      (r, n) ->
          new PropertyNotice(
              r.getObject("id", UUID.class),
              r.getObject("property_id", UUID.class),
              r.getObject("lease_id", UUID.class),
              r.getObject("tenant_id", UUID.class),
              r.getObject("owner_organization_id", UUID.class),
              r.getString("property_name"),
              r.getString("unit_label"),
              r.getString("tenant_name"),
              r.getString("kind"),
              r.getString("title"),
              r.getString("message"),
              instant(r, "starts_at"),
              instant(r, "ends_at"),
              r.getString("status"),
              r.getString("tenant_response"),
              r.getLong("version"),
              r.getObject("created_by", UUID.class),
              r.getObject("created_by_organization_id", UUID.class),
              instant(r, "published_at"),
              r.getObject("published_by", UUID.class),
              instant(r, "cancelled_at"),
              instant(r, "created_at"),
              instant(r, "updated_at"));

  public List<UUID> occupancy(UUID property, int l, int f) {
    return jdbc.query(
        "SELECT id FROM rental_leases WHERE property_id=? AND status"
            + " IN('SIGNED','ACTIVE','COMPLETED','TERMINATED') ORDER BY (unit_released_at IS NULL)"
            + " DESC,updated_at DESC,id LIMIT ? OFFSET ?",
        (r, n) -> r.getObject("id", UUID.class),
        property,
        l,
        f);
  }

  public Optional<PropertyNotice> find(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM property_operational_notices WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            mapper,
            id)
        .stream()
        .findFirst();
  }

  public List<PropertyNotice> list(
      UUID org, UUID property, UUID actor, boolean tenant, int l, int f) {
    if (tenant)
      return jdbc.query(
          "SELECT * FROM property_operational_notices WHERE tenant_id=? AND published_at IS NOT"
              + " NULL ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
          mapper,
          actor,
          l,
          f);
    return jdbc.query(
        "SELECT * FROM property_operational_notices WHERE property_id=? AND"
            + " caretaker_property_access(?,property_id,?,'NOTICES') ORDER BY created_at DESC,id"
            + " LIMIT ? OFFSET ?",
        mapper,
        property,
        org,
        actor,
        l,
        f);
  }

  public void create(
      UUID id,
      UUID actor,
      UUID org,
      CaretakerOccupancyResponse occupancy,
      Property property,
      UUID tenant,
      PropertyNoticeCreateRequest r,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " property_operational_notices(id,property_id,lease_id,tenant_id,owner_organization_id,property_name,unit_label,tenant_name,kind,title,message,starts_at,ends_at,status,version,created_by,created_by_organization_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?,?,?)",
        id,
        property.id(),
        r.leaseId(),
        tenant,
        property.ownerOrganizationId(),
        property.name(),
        occupancy.unitLabel(),
        occupancy.tenantName(),
        r.kind(),
        r.title().trim(),
        r.message().trim(),
        time(r.startsAt()),
        time(r.endsAt()),
        actor,
        org,
        time(now),
        time(now));
  }

  public void publish(UUID id, UUID actor, Instant now) {
    jdbc.update(
        "UPDATE property_operational_notices SET"
            + " status='PUBLISHED',published_at=?,published_by=?,version=version+1,updated_at=?"
            + " WHERE id=?",
        time(now),
        actor,
        time(now),
        id);
  }

  public void cancel(UUID id, Instant now) {
    jdbc.update(
        "UPDATE property_operational_notices SET"
            + " status='CANCELLED',cancelled_at=?,version=version+1,updated_at=? WHERE id=?",
        time(now),
        time(now),
        id);
  }

  public void reply(UUID id, String response, Instant now) {
    jdbc.update(
        "UPDATE property_operational_notices SET"
            + " tenant_response=COALESCE(?,tenant_response),version=version+1,updated_at=? WHERE"
            + " id=?",
        response,
        time(now),
        id);
  }

  public void notifyTenant(UUID id) {
    var n = find(id, false).orElseThrow();
    if (n.publishedAt() == null) return;
    UUID event =
        jdbc.queryForObject(
            "SELECT id FROM caretaker_events WHERE target_kind='NOTICE' AND target_id=? ORDER BY"
                + " created_at DESC,id LIMIT 1",
            UUID.class,
            id);
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,type,message,caretaker_target_id,caretaker_event_id,caretaker_url)"
            + " VALUES(?,?,'PROPERTY_NOTICE','Your property manager has a notice update.',?,?,?) ON"
            + " CONFLICT DO NOTHING",
        UUID.randomUUID(),
        n.tenantId(),
        id,
        event,
        "/api/v1/me/property-notices/" + id);
  }
}
