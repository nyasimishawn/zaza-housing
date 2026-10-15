package com.zaza.housing.repository;

import com.zaza.housing.dto.ConversationReportQueueResponse;
import com.zaza.housing.dto.ReportConversationMessageRequest;
import com.zaza.housing.enums.ConversationReportCategory;
import com.zaza.housing.model.ConversationReport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationModerationRepository {
  private final JdbcTemplate jdbc;

  public ConversationModerationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<ConversationReport> REPORT =
      (r, n) ->
          new ConversationReport(
              r.getObject("id", UUID.class),
              r.getObject("conversation_id", UUID.class),
              r.getObject("message_id", UUID.class),
              r.getObject("reporter_id", UUID.class),
              ConversationReportCategory.valueOf(r.getString("category")),
              r.getString("reason"),
              r.getString("status"),
              r.getObject("assigned_to", UUID.class),
              r.getString("resolution"),
              r.getString("resolution_reason"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant());

  public UUID create(
      UUID conversation,
      UUID message,
      UUID actor,
      ReportConversationMessageRequest r,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " conversation_reports(id,conversation_id,message_id,reporter_id,category,reason,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        conversation,
        message,
        actor,
        r.category().name(),
        r.reason().trim(),
        Timestamp.from(now));
    return id;
  }

  public Optional<ConversationReport> find(UUID id) {
    return jdbc.query("SELECT * FROM conversation_reports WHERE id=?", REPORT, id).stream()
        .findFirst();
  }

  public List<ConversationReport> own(UUID actor, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM conversation_reports WHERE reporter_id=? ORDER BY created_at DESC,id LIMIT ?"
            + " OFFSET ?",
        REPORT,
        actor,
        limit,
        offset);
  }

  public List<ConversationReportQueueResponse> queue(
      UUID actor, String status, boolean mine, int limit, int offset) {
    var args = new ArrayList<Object>();
    args.add(actor);
    String where = " WHERE true";
    if (status != null) {
      where += " AND status=?";
      args.add(status);
    }
    if (mine) {
      where += " AND assigned_to=?";
      args.add(actor);
    }
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT id,category,status,version,assigned_to=? AS assigned_to_me,created_at FROM"
            + " conversation_reports"
            + where
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        (r, n) ->
            new ConversationReportQueueResponse(
                r.getObject("id", UUID.class),
                ConversationReportCategory.valueOf(r.getString("category")),
                r.getString("status"),
                r.getLong("version"),
                r.getBoolean("assigned_to_me"),
                r.getTimestamp("created_at").toInstant()),
        args.toArray());
  }

  public void assign(UUID id, UUID actor) {
    jdbc.update(
        "UPDATE conversation_reports SET assigned_to=?,status='ASSIGNED',version=version+1 WHERE"
            + " id=?",
        actor,
        id);
  }

  public void release(UUID id) {
    jdbc.update(
        "UPDATE conversation_reports SET assigned_to=NULL,status='OPEN',version=version+1 WHERE"
            + " id=?",
        id);
  }

  public void resolve(UUID id, String resolution, String reason) {
    jdbc.update(
        "UPDATE conversation_reports SET"
            + " resolution=?,resolution_reason=?,status='RESOLVED',version=version+1 WHERE id=?",
        resolution,
        reason.trim(),
        id);
  }

  public void decision(UUID id, UUID actor, String action, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO conversation_report_decisions(id,report_id,actor_id,action,reason,created_at)"
            + " VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        actor,
        action,
        reason.trim(),
        Timestamp.from(now));
  }
}
