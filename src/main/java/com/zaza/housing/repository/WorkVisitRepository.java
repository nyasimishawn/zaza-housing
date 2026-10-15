package com.zaza.housing.repository;

import com.zaza.housing.dto.CreateServiceQuoteRequest;
import com.zaza.housing.dto.ProposeWorkVisitRequest;
import com.zaza.housing.dto.ProviderCalendarResponse;
import com.zaza.housing.dto.ServiceQuoteResponse;
import com.zaza.housing.dto.WorkVisitResponse;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.model.WorkRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkVisitRepository {
  private final JdbcTemplate jdbc;

  public WorkVisitRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID quote(WorkRequest work, CreateServiceQuoteRequest r, String currency) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_quotes(id,work_id,assignment_id,amount,currency,scope,valid_until)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        work.id(),
        work.assignmentId(),
        r.amount(),
        currency,
        r.scope().trim(),
        java.sql.Timestamp.from(r.validUntil()));
    return id;
  }

  public List<ServiceQuoteResponse> quotes(UUID work, UUID assignment, int limit, int offset) {
    String sql =
        "SELECT q.*,EXISTS(SELECT 1 FROM service_quote_acceptances a WHERE a.quote_id=q.id) AS"
            + " accepted FROM service_quotes q WHERE q.work_id=?"
            + (assignment == null ? "" : " AND q.assignment_id=?")
            + " ORDER BY q.created_at DESC,q.id LIMIT ? OFFSET ?";
    var args = new ArrayList<Object>();
    args.add(work);
    if (assignment != null) args.add(assignment);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql,
        (r, n) ->
            new ServiceQuoteResponse(
                r.getObject("id", UUID.class),
                r.getObject("assignment_id", UUID.class),
                r.getBigDecimal("amount"),
                r.getString("currency"),
                r.getString("scope"),
                r.getTimestamp("valid_until").toInstant(),
                r.getBoolean("accepted") ? "ACCEPTED" : "PENDING",
                r.getTimestamp("created_at").toInstant()),
        args.toArray());
  }

  public Optional<ServiceQuoteResponse> quote(UUID work, UUID id) {
    return jdbc
        .query(
            "SELECT q.*,EXISTS(SELECT 1 FROM service_quote_acceptances a WHERE a.quote_id=q.id) AS"
                + " accepted FROM service_quotes q WHERE q.work_id=? AND q.id=?",
            (r, n) ->
                new ServiceQuoteResponse(
                    id,
                    r.getObject("assignment_id", UUID.class),
                    r.getBigDecimal("amount"),
                    r.getString("currency"),
                    r.getString("scope"),
                    r.getTimestamp("valid_until").toInstant(),
                    r.getBoolean("accepted") ? "ACCEPTED" : "PENDING",
                    r.getTimestamp("created_at").toInstant()),
            work,
            id)
        .stream()
        .findFirst();
  }

  public void acceptQuote(UUID id, UUID actor, String message) {
    jdbc.update(
        "INSERT INTO service_quote_acceptances(quote_id,actor_id,message) VALUES (?,?,?)",
        id,
        actor,
        message);
  }

  public UUID propose(WorkRequest work, ProposeWorkVisitRequest r) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO work_visits(id,work_id,assignment_id,start_at,end_at,access_instructions)"
            + " VALUES (?,?,?,?,?,?)",
        id,
        work.id(),
        work.assignmentId(),
        java.sql.Timestamp.from(r.startAt()),
        java.sql.Timestamp.from(r.endAt()),
        r.accessInstructions().trim());
    return id;
  }

  public List<WorkVisitResponse> visits(UUID work, UUID assignment, int limit, int offset) {
    var args = new ArrayList<Object>();
    args.add(work);
    if (assignment != null) args.add(assignment);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT v.*,EXISTS(SELECT 1 FROM work_visit_acceptances a WHERE a.visit_id=v.id) AS"
            + " accepted FROM work_visits v WHERE v.work_id=?"
            + (assignment == null ? "" : " AND v.assignment_id=?")
            + " ORDER BY v.created_at DESC,v.id LIMIT ? OFFSET ?",
        (r, n) ->
            new WorkVisitResponse(
                r.getObject("id", UUID.class),
                r.getObject("assignment_id", UUID.class),
                r.getTimestamp("start_at").toInstant(),
                r.getTimestamp("end_at").toInstant(),
                r.getString("access_instructions"),
                r.getBoolean("accepted") ? "ACCEPTED" : "PENDING",
                r.getTimestamp("created_at").toInstant()),
        args.toArray());
  }

  public Optional<WorkVisitResponse> visit(UUID work, UUID id) {
    return jdbc
        .query(
            "SELECT v.*,EXISTS(SELECT 1 FROM work_visit_acceptances a WHERE a.visit_id=v.id) AS"
                + " accepted FROM work_visits v WHERE v.work_id=? AND v.id=?",
            (r, n) ->
                new WorkVisitResponse(
                    id,
                    r.getObject("assignment_id", UUID.class),
                    r.getTimestamp("start_at").toInstant(),
                    r.getTimestamp("end_at").toInstant(),
                    r.getString("access_instructions"),
                    r.getBoolean("accepted") ? "ACCEPTED" : "PENDING",
                    r.getTimestamp("created_at").toInstant()),
            work,
            id)
        .stream()
        .findFirst();
  }

  public void acceptVisit(UUID id, UUID actor, String message) {
    jdbc.update(
        "INSERT INTO work_visit_acceptances(visit_id,actor_id,message) VALUES (?,?,?)",
        id,
        actor,
        message);
  }

  public boolean overlaps(UUID provider, UUID work, Instant start, Instant end) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM work_requests w JOIN work_visits v ON v.id=w.visit_id"
                + " WHERE w.provider_id=? AND w.id<>? AND w.status IN"
                + " ('SCHEDULED','IN_PROGRESS','AWAITING_CONFIRMATION','AWAITING_APPROVAL','DISPUTED')"
                + " AND v.start_at<? AND v.end_at>?)",
            Boolean.class,
            provider,
            work,
            java.sql.Timestamp.from(end),
            java.sql.Timestamp.from(start)));
  }

  public List<ProviderCalendarResponse> calendar(UUID provider, Instant from, Instant to) {
    return jdbc.query(
        "SELECT w.id,w.status,v.start_at,v.end_at FROM work_requests w JOIN work_visits v ON"
            + " v.id=w.visit_id WHERE w.provider_id=? AND w.status IN"
            + " ('SCHEDULED','IN_PROGRESS','AWAITING_CONFIRMATION','AWAITING_APPROVAL','DISPUTED')"
            + " AND v.start_at<? AND v.end_at>? ORDER BY v.start_at,w.id",
        (r, n) ->
            new ProviderCalendarResponse(
                r.getObject("id", UUID.class),
                r.getTimestamp("start_at").toInstant(),
                r.getTimestamp("end_at").toInstant(),
                WorkStatus.valueOf(r.getString("status"))),
        provider,
        java.sql.Timestamp.from(to),
        java.sql.Timestamp.from(from));
  }
}
