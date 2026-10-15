package com.zaza.housing.repository;

import com.zaza.housing.dto.CreateServiceReviewRequest;
import com.zaza.housing.dto.CreateWorkRequest;
import com.zaza.housing.dto.ServiceOfferResponse;
import com.zaza.housing.dto.WorkDashboardResponse;
import com.zaza.housing.dto.WorkDisputeResponse;
import com.zaza.housing.dto.WorkEventResponse;
import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.model.WorkRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class WorkRequestRepository {

  public Map<UUID, WorkRequest> findAll(List<UUID> ids) {
    var result = new HashMap<UUID, WorkRequest>();
    if (ids.isEmpty()) return result;
    var rows =
        jdbc.query(
            "SELECT * FROM work_requests WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ")",
            WORK,
            ids.toArray());
    rows.forEach(w -> result.put(w.id(), w));
    return result;
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  private static Instant time(java.sql.ResultSet r, String name) throws java.sql.SQLException {
    var value = r.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private static final RowMapper<WorkRequest> WORK =
      (r, n) ->
          new WorkRequest(
              r.getObject("id", UUID.class),
              WorkKind.valueOf(r.getString("kind")),
              r.getObject("lease_id", UUID.class),
              r.getObject("property_id", UUID.class),
              r.getObject("unit_id", UUID.class),
              r.getObject("tenant_id", UUID.class),
              r.getObject("reporter_id", UUID.class),
              r.getObject("owner_organization_id", UUID.class),
              ServiceCategory.valueOf(r.getString("category")),
              r.getString("title"),
              r.getString("description"),
              WorkPriority.valueOf(r.getString("priority")),
              WorkStatus.valueOf(r.getString("status")),
              r.getLong("version"),
              r.getObject("provider_id", UUID.class),
              r.getObject("assignment_id", UUID.class),
              r.getObject("quote_id", UUID.class),
              r.getObject("visit_id", UUID.class),
              r.getObject("dispute_id", UUID.class),
              r.getString("country_code"),
              r.getString("city"),
              r.getString("address"),
              r.getString("unit_label"),
              r.getString("completion_summary"),
              time(r, "completed_at"),
              time(r, "created_at"),
              time(r, "updated_at"));

  public WorkRequestRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<WorkRequest> find(UUID id) {
    return jdbc.query("SELECT * FROM work_requests WHERE id=?", WORK, id).stream().findFirst();
  }

  public UUID create(
      RentalLease lease,
      UUID actor,
      CreateWorkRequest request,
      String country,
      String city,
      String address,
      String unitLabel) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO work_requests(id,kind,lease_id,property_id,unit_id,tenant_id,reporter_id,owner_organization_id,category,title,description,priority,status,country_code,city,address,unit_label)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'SUBMITTED',?,?,?,?)
        """,
        id,
        request.kind().name(),
        lease.id(),
        lease.propertyId(),
        lease.unitId(),
        lease.tenantId(),
        actor,
        lease.ownerOrganizationId(),
        request.category().name(),
        request.title().trim(),
        request.description().trim(),
        request.priority().name(),
        country,
        city,
        address,
        unitLabel);
    return id;
  }

  public void state(UUID id, WorkStatus status) {
    jdbc.update(
        "UPDATE work_requests SET status=?,updated_at=clock_timestamp() WHERE id=?",
        status.name(),
        id);
  }

  public void cancel(UUID id, boolean rejected) {
    jdbc.update(
        "UPDATE work_requests SET"
            + " status=?,provider_id=NULL,assignment_id=NULL,quote_id=NULL,visit_id=NULL WHERE"
            + " id=?",
        rejected ? "REJECTED" : "CANCELLED",
        id);
  }

  public void priority(UUID id, WorkPriority priority) {
    jdbc.update("UPDATE work_requests SET priority=? WHERE id=?", priority.name(), id);
  }

  public void touch(UUID id) {
    jdbc.update(
        "UPDATE work_requests SET version=version+1,updated_at=clock_timestamp() WHERE id=?", id);
  }

  public UUID assign(UUID work, ServiceOfferResponse offer) {
    UUID assignment = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO work_assignments(id,work_id,provider_id,offer_id,snapshot) VALUES"
            + " (?,?,?,?,CAST(? AS jsonb))",
        assignment,
        work,
        offer.providerId(),
        offer.id(),
        json.writeValueAsString(offer));
    jdbc.update(
        "UPDATE work_requests SET"
            + " status='ASSIGNED',provider_id=?,assignment_id=?,quote_id=NULL,visit_id=NULL WHERE"
            + " id=?",
        offer.providerId(),
        assignment,
        work);
    return assignment;
  }

  public void release(WorkRequest work) {
    jdbc.update(
        "UPDATE work_requests SET"
            + " provider_id=NULL,assignment_id=NULL,quote_id=NULL,visit_id=NULL,status=? WHERE"
            + " id=?",
        work.kind() == WorkKind.MAINTENANCE ? "TRIAGED" : "REJECTED",
        work.id());
  }

  public void quote(UUID work, UUID quote) {
    jdbc.update("UPDATE work_requests SET quote_id=?,visit_id=NULL WHERE id=?", quote, work);
  }

  public void visit(UUID work, UUID visit) {
    jdbc.update("UPDATE work_requests SET visit_id=? WHERE id=?", visit, work);
  }

  public void completion(UUID work, String summary, WorkStatus status) {
    jdbc.update(
        "UPDATE work_requests SET completion_summary=?,status=? WHERE id=?",
        summary,
        status.name(),
        work);
  }

  public void completed(UUID work, Instant now) {
    jdbc.update(
        "UPDATE work_requests SET status='COMPLETED',completed_at=? WHERE id=?",
        java.sql.Timestamp.from(now),
        work);
  }

  public UUID dispute(WorkRequest work, WorkParty party, String reason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO work_disputes(id,work_id,party,reason,previous_status) VALUES (?,?,?,?,?)",
        id,
        work.id(),
        party.name(),
        reason,
        work.status().name());
    jdbc.update(
        "UPDATE work_requests SET dispute_id=?,status='DISPUTED' WHERE id=?", id, work.id());
    return id;
  }

  public void resolve(WorkRequest work, UUID actor, String resolution, String message) {
    jdbc.update(
        "INSERT INTO work_dispute_resolutions(dispute_id,actor_id,resolution,message) VALUES"
            + " (?,?,?,?)",
        work.disputeId(),
        actor,
        resolution,
        message);
    if (resolution.equals("REWORK"))
      jdbc.update(
          "UPDATE work_requests SET"
              + " status='ACCEPTED',visit_id=NULL,completion_summary=NULL,completed_at=NULL WHERE"
              + " id=?",
          work.id());
    else {
      String prior =
          jdbc.queryForObject(
              "SELECT previous_status FROM work_disputes WHERE id=?",
              String.class,
              work.disputeId());
      if (!prior.equals("SCHEDULED") && !prior.equals("IN_PROGRESS"))
        prior = "AWAITING_CONFIRMATION";
      jdbc.update(
          "UPDATE work_requests SET status=?,completed_at=NULL WHERE id=?", prior, work.id());
    }
  }

  public ServiceOfferResponse assignedOffer(WorkRequest work) {
    return jdbc.query(
            "SELECT snapshot FROM work_assignments WHERE work_id=? AND id=?",
            (r, n) -> json.readValue(r.getString(1), ServiceOfferResponse.class),
            work.id(),
            work.assignmentId())
        .getFirst();
  }

  public boolean blocksCheckout(UUID lease) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM work_requests WHERE lease_id=? AND kind='MAINTENANCE' AND"
                + " status IN"
                + " ('IN_PROGRESS','AWAITING_CONFIRMATION','AWAITING_APPROVAL','DISPUTED'))",
            Boolean.class,
            lease));
  }

  public WorkParty disputeParty(UUID id) {
    return WorkParty.valueOf(
        jdbc.queryForObject("SELECT party FROM work_disputes WHERE id=?", String.class, id));
  }

  public List<WorkDisputeResponse> disputes(UUID id, int limit, int offset) {
    return jdbc.query(
        "SELECT d.*,r.resolution,r.message AS resolution_message FROM work_disputes d LEFT JOIN"
            + " work_dispute_resolutions r ON r.dispute_id=d.id WHERE d.work_id=? ORDER BY"
            + " d.created_at DESC,d.id LIMIT ? OFFSET ?",
        (r, n) ->
            new WorkDisputeResponse(
                r.getObject("id", UUID.class),
                r.getString("reason"),
                WorkParty.valueOf(r.getString("party")),
                r.getString("resolution"),
                r.getString("resolution_message"),
                time(r, "created_at")),
        id,
        limit,
        offset);
  }

  public Optional<Map<String, Object>> operation(UUID scope, UUID request) {
    return jdbc
        .queryForList(
            "SELECT actor_id,action,fingerprint,work_id FROM work_operations WHERE scope_id=? AND"
                + " request_id=?",
            scope,
            request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID scope, UUID request, UUID actor, String action, String fingerprint, UUID work) {
    jdbc.update(
        "INSERT INTO work_operations(scope_id,request_id,actor_id,action,fingerprint,work_id)"
            + " VALUES (?,?,?,?,?,?)",
        scope,
        request,
        actor,
        action,
        fingerprint,
        work);
  }

  public UUID event(
      WorkRequest work,
      UUID actor,
      WorkParty party,
      String type,
      WorkVisibility visibility,
      String message) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " work_events(id,work_id,assignment_id,provider_id,actor_id,party,type,status,visibility,message)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        work.id(),
        work.assignmentId(),
        work.providerId(),
        actor,
        party.name(),
        type,
        work.status().name(),
        visibility.name(),
        message);
    return id;
  }

  public List<WorkEventResponse> history(WorkRequest work, WorkParty party, int limit, int offset) {
    String where =
        party == WorkParty.COORDINATOR
            ? ""
            : party == WorkParty.CUSTOMER
                ? " AND visibility='SHARED'"
                : " AND visibility='SHARED' AND (assignment_id=? OR assignment_id IS NULL)";
    var args = new ArrayList<Object>();
    args.add(work.id());
    if (party == WorkParty.PROVIDER) args.add(work.assignmentId());
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM work_events WHERE work_id=?"
            + where
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        (r, n) ->
            new WorkEventResponse(
                r.getObject("id", UUID.class),
                r.getString("type"),
                WorkStatus.valueOf(r.getString("status")),
                WorkParty.valueOf(r.getString("party")),
                WorkVisibility.valueOf(r.getString("visibility")),
                r.getString("message"),
                time(r, "created_at")),
        args.toArray());
  }

  private String scope(WorkParty party) {
    return switch (party) {
      case CUSTOMER -> "tenant_id=?";
      case COORDINATOR -> "property_id=? AND kind='MAINTENANCE'";
      case PROVIDER -> "provider_id=?";
    };
  }

  public List<WorkRequest> list(
      WorkParty party,
      UUID scope,
      WorkStatus status,
      WorkKind kind,
      WorkPriority priority,
      int limit,
      int offset) {
    var args = new ArrayList<Object>();
    args.add(scope);
    StringBuilder sql = new StringBuilder("SELECT * FROM work_requests WHERE " + scope(party));
    if (status != null) {
      sql.append(" AND status=?");
      args.add(status.name());
    }
    if (kind != null) {
      sql.append(" AND kind=?");
      args.add(kind.name());
    }
    if (priority != null) {
      sql.append(" AND priority=?");
      args.add(priority.name());
    }
    sql.append(" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), WORK, args.toArray());
  }

  public WorkDashboardResponse summary(WorkParty party, UUID scope, Instant now) {
    var counts = new EnumMap<WorkStatus, Long>(WorkStatus.class);
    for (var s : WorkStatus.values()) counts.put(s, 0L);
    jdbc.query(
        "SELECT status,count(*) AS total FROM work_requests WHERE "
            + scope(party)
            + " GROUP BY status",
        r -> {
          counts.put(WorkStatus.valueOf(r.getString("status")), r.getLong("total"));
        },
        scope);
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long closed =
        counts.get(WorkStatus.COMPLETED)
            + counts.get(WorkStatus.CANCELLED)
            + counts.get(WorkStatus.REJECTED);
    long overdue =
        jdbc.queryForObject(
            "SELECT count(*) FROM work_requests w JOIN work_visits v ON v.id=w.visit_id WHERE "
                + scope(party)
                + " AND w.status='SCHEDULED' AND v.end_at<?",
            Long.class,
            scope,
            java.sql.Timestamp.from(now));
    return new WorkDashboardResponse(total, Map.copyOf(counts), total - closed, overdue);
  }

  public UUID review(WorkRequest work, UUID actor, CreateServiceReviewRequest request) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " service_reviews(id,work_id,assignment_id,provider_id,reviewer_id,rating,comment)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        work.id(),
        work.assignmentId(),
        work.providerId(),
        actor,
        request.rating(),
        request.comment().trim());
    return id;
  }
}
