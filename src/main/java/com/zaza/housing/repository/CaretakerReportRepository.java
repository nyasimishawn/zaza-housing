package com.zaza.housing.repository;

import com.zaza.housing.dto.CaretakerPerformanceResponse;
import com.zaza.housing.dto.CaretakerReportResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaretakerReportRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public CaretakerReportRepository(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  private static final String TASK_SCOPE =
      "caretaker_property_access(:org,t.property_id,:actor,'OPERATIONS') AND (CAST(:property AS"
          + " uuid) IS NULL OR t.property_id=:property) AND (:manager OR"
          + " (t.assignee_organization_id=:org AND t.assignee_id=:actor))";

  private Map<String, Long> counts(String sql, Map<String, Object> a) {
    var out = new LinkedHashMap<String, Long>();
    jdbc.query(
        sql,
        a,
        r -> {
          out.put(r.getString(1), r.getLong(2));
        });
    return Map.copyOf(out);
  }

  public CaretakerReportResponse report(
      UUID o, UUID actor, UUID p, boolean manager, Instant from, Instant to) {
    var a = new HashMap<String, Object>();
    a.put("org", o);
    a.put("actor", actor);
    a.put("property", p);
    a.put("manager", manager);
    a.put("from", Timestamp.from(from));
    a.put("to", Timestamp.from(to));
    var states =
        counts(
            "SELECT t.status,count(*) FROM property_operations_tasks t WHERE "
                + TASK_SCOPE
                + " GROUP BY t.status",
            a);
    long overdue =
        jdbc.queryForObject(
            "SELECT count(*) FROM property_operations_tasks t WHERE "
                + TASK_SCOPE
                + " AND t.status NOT IN('COMPLETED','CANCELLED') AND"
                + " t.due_at<statement_timestamp()",
            a,
            Long.class);
    long created =
        jdbc.queryForObject(
            "SELECT count(*) FROM property_operations_tasks t WHERE "
                + TASK_SCOPE
                + " AND t.created_at>=:from AND t.created_at<:to",
            a,
            Long.class);
    long completed =
        jdbc.queryForObject(
            "SELECT count(*) FROM property_operations_tasks t WHERE "
                + TASK_SCOPE
                + " AND t.completed_at>=:from AND t.completed_at<:to",
            a,
            Long.class);
    var inspections =
        counts(
            "SELECT e.action,count(*) FROM lease_inspection_events e JOIN rental_leases l ON"
                + " l.id=e.lease_id WHERE"
                + " caretaker_property_access(:org,l.property_id,:actor,'INSPECTIONS') AND"
                + " (CAST(:property AS uuid) IS NULL OR l.property_id=:property) AND (:manager OR"
                + " e.actor_id=:actor) AND e.party='LESSOR' AND e.created_at>=:from AND"
                + " e.created_at<:to GROUP BY e.action",
            a);
    var maintenance =
        counts(
            "SELECT e.type,count(*) FROM work_events e JOIN work_requests w ON w.id=e.work_id WHERE"
                + " w.kind='MAINTENANCE' AND"
                + " caretaker_property_access(:org,w.property_id,:actor,'MAINTENANCE') AND"
                + " (CAST(:property AS uuid) IS NULL OR w.property_id=:property) AND (:manager OR"
                + " e.actor_id=:actor) AND e.party='COORDINATOR' AND e.created_at>=:from AND"
                + " e.created_at<:to GROUP BY e.type",
            a);
    var people =
        jdbc.query(
            "SELECT t.assignee_id,t.assignee_name,count(*) FILTER(WHERE t.completed_at>=:from AND"
                + " t.completed_at<:to) AS done,count(*) FILTER(WHERE t.status='SUBMITTED') AS"
                + " submitted,count(*) FILTER(WHERE t.status IN('OPEN','IN_PROGRESS','BLOCKED')) AS"
                + " open FROM property_operations_tasks t WHERE "
                + TASK_SCOPE
                + " GROUP BY t.assignee_id,t.assignee_name ORDER BY done"
                + " DESC,t.assignee_name,t.assignee_id",
            a,
            (r, n) ->
                new CaretakerPerformanceResponse(
                    r.getObject("assignee_id", UUID.class),
                    r.getString("assignee_name"),
                    r.getLong("done"),
                    r.getLong("submitted"),
                    r.getLong("open")));
    return new CaretakerReportResponse(
        from, to, manager, states, overdue, created, completed, inspections, maintenance, people);
  }
}
