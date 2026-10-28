package com.zaza.housing.repository;

import com.zaza.housing.dto.AgencyAgentPerformance;
import com.zaza.housing.dto.AgencyCommissionTotal;
import com.zaza.housing.dto.AgencyReportResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgencyReportRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public AgencyReportRepository(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  public AgencyReportResponse report(
      UUID org,
      UUID actor,
      boolean manager,
      LocalDate from,
      LocalDate to,
      Instant now,
      int limit,
      int offset) {
    var p = new HashMap<String, Object>();
    p.put("org", org);
    p.put("actor", actor);
    p.put("manager", manager);
    p.put("from", Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
    p.put("to", Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    p.put("now", Timestamp.from(now));
    p.put("limit", limit);
    p.put("offset", offset);
    String lead =
        " FROM agency_leads l WHERE l.agency_id=:org AND " + AgencyRepository.LEAD_VISIBLE;
    var stages = new LinkedHashMap<String, Long>();
    jdbc.query(
        "SELECT l.stage,count(*) total" + lead + " GROUP BY l.stage",
        p,
        r -> {
          stages.put(r.getString("stage"), r.getLong("total"));
        });
    long created =
        jdbc.queryForObject(
            "SELECT count(*)" + lead + " AND l.created_at>=:from AND l.created_at<:to",
            p,
            Long.class);
    long won =
        jdbc.queryForObject(
            "SELECT count(*) FROM agency_leads l WHERE l.agency_id=:org AND (:manager OR"
                + " l.won_agent_id=:actor) AND l.won_at>=:from AND l.won_at<:to",
            p,
            Long.class);
    String task =
        " FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id WHERE t.agency_id=:org AND "
            + AgencyRepository.LEAD_VISIBLE
            + " AND (:manager OR t.assignee_id=:actor)";
    long completed =
        jdbc.queryForObject(
            "SELECT count(*)"
                + task
                + " AND t.status='DONE' AND t.updated_at>=:from AND t.updated_at<:to",
            p,
            Long.class);
    long overdue =
        jdbc.queryForObject(
            "SELECT count(*)" + task + " AND t.status='OPEN' AND t.due_at<:now", p, Long.class);
    var agents =
        jdbc.query(
            """
            SELECT u.id,u.display_name,
            (SELECT count(*) FROM agency_leads l WHERE l.agency_id=:org AND l.assigned_to=u.id AND l.created_at>=:from AND l.created_at<:to AND
            """
                + AgencyRepository.LEAD_VISIBLE
                + """
                ) leads_created,
                (SELECT count(*) FROM agency_leads l WHERE l.agency_id=:org AND l.won_agent_id=u.id AND l.won_at>=:from AND l.won_at<:to) deals_won,
                (SELECT count(*) FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id WHERE t.agency_id=:org AND t.assignee_id=u.id AND t.status='DONE' AND t.updated_at>=:from AND t.updated_at<:to AND
                """
                + AgencyRepository.LEAD_VISIBLE
                + """
                ) tasks_completed,
                (SELECT count(*) FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id WHERE t.agency_id=:org AND t.assignee_id=u.id AND t.status='OPEN' AND
                """
                + AgencyRepository.LEAD_VISIBLE
                + """
                ) open_tasks
                FROM app_users u WHERE (:manager OR u.id=:actor) AND
                  (EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=:org AND m.user_id=u.id)
                   OR EXISTS(SELECT 1 FROM agency_leads l WHERE l.agency_id=:org AND l.won_agent_id=u.id))
                ORDER BY u.display_name,u.id LIMIT :limit OFFSET :offset
                """,
            p,
            (r, n) ->
                new AgencyAgentPerformance(
                    r.getObject("id", UUID.class),
                    r.getString("display_name"),
                    r.getLong("leads_created"),
                    r.getLong("deals_won"),
                    r.getLong("tasks_completed"),
                    r.getLong("open_tasks")));
    var commissions =
        jdbc.query(
            """
            SELECT currency,
              COALESCE(sum(amount) FILTER(WHERE created_at>=:from AND created_at<:to AND status NOT IN('REJECTED','VOID')),0) earned,
              COALESCE(sum(amount) FILTER(WHERE approved_at>=:from AND approved_at<:to AND status IN('APPROVED','PAID')),0) approved,
              COALESCE(sum(amount) FILTER(WHERE paid_at>=:from AND paid_at<:to AND status='PAID'),0) paid
            FROM agency_commissions WHERE agency_id=:org AND (:manager OR agent_id=:actor)
            AND ((created_at>=:from AND created_at<:to) OR (approved_at>=:from AND approved_at<:to) OR (paid_at>=:from AND paid_at<:to))
            GROUP BY currency ORDER BY currency
            """,
            p,
            (r, n) ->
                new AgencyCommissionTotal(
                    r.getString("currency"),
                    r.getBigDecimal("earned"),
                    r.getBigDecimal("approved"),
                    r.getBigDecimal("paid")));
    return new AgencyReportResponse(
        from,
        to,
        manager,
        Map.copyOf(stages),
        created,
        won,
        completed,
        overdue,
        agents,
        commissions);
  }
}
