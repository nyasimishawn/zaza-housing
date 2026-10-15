package com.zaza.housing.repository;

import com.zaza.housing.dto.ProposeViewingRequest;
import com.zaza.housing.dto.ViewingHistoryResponse;
import com.zaza.housing.dto.ViewingProposalResponse;
import com.zaza.housing.dto.ViewingResponse;
import com.zaza.housing.enums.ConversationRole;
import com.zaza.housing.model.ViewingAppointment;
import com.zaza.housing.model.ViewingProposal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ViewingRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public ViewingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  private static UUID id(ResultSet r, String c) throws SQLException {
    return r.getObject(c, UUID.class);
  }

  private static final RowMapper<ViewingAppointment> APPOINTMENT =
      (r, n) ->
          new ViewingAppointment(
              id(r, "id"),
              id(r, "conversation_id"),
              id(r, "current_proposal_id"),
              id(r, "confirmed_proposal_id"),
              r.getString("status"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant());
  private static final RowMapper<ViewingProposal> PROPOSAL =
      (r, n) ->
          new ViewingProposal(
              id(r, "id"),
              id(r, "viewing_id"),
              id(r, "proposed_by"),
              ConversationRole.valueOf(r.getString("proposed_role")),
              r.getTimestamp("start_at").toInstant(),
              r.getTimestamp("end_at").toInstant(),
              r.getLong("listing_version"),
              r.getString("meeting_instructions"),
              r.getString("reason"),
              r.getTimestamp("created_at").toInstant());

  public Optional<ViewingAppointment> find(UUID conversation, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM viewing_appointments WHERE conversation_id=? AND id=?",
            APPOINTMENT,
            conversation,
            id)
        .stream()
        .findFirst();
  }

  public ViewingProposal proposal(UUID id) {
    return jdbc.queryForObject("SELECT * FROM viewing_proposals WHERE id=?", PROPOSAL, id);
  }

  public void lockCustomer(UUID customer) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (r, n) -> 0,
        "viewing-customer:" + customer);
  }

  public boolean active(UUID conversation) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM viewing_appointments WHERE conversation_id=? AND status IN"
                + " ('PENDING','CONFIRMED','RESCHEDULE_PENDING'))",
            Boolean.class,
            conversation));
  }

  public UUID create(UUID conversation, Instant now) {
    UUID id = UUID.randomUUID(), proposal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO viewing_appointments(id,conversation_id,current_proposal_id,created_at) VALUES"
            + " (?,?,?,?)",
        id,
        conversation,
        proposal,
        Timestamp.from(now));
    return id;
  }

  public UUID propose(
      ViewingAppointment appointment,
      UUID actor,
      ConversationRole role,
      ProposeViewingRequest r,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " viewing_proposals(id,viewing_id,proposed_by,proposed_role,start_at,end_at,listing_version,meeting_instructions,reason,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        appointment.id(),
        actor,
        role.name(),
        Timestamp.from(r.startAt()),
        Timestamp.from(r.endAt()),
        r.listingVersion(),
        r.meetingInstructions().trim(),
        r.reason().trim(),
        Timestamp.from(now));
    jdbc.update(
        "UPDATE viewing_appointments SET current_proposal_id=?,status=CASE WHEN"
            + " confirmed_proposal_id IS NULL THEN 'PENDING' ELSE 'RESCHEDULE_PENDING' END WHERE"
            + " id=?",
        id,
        appointment.id());
    return id;
  }

  public void touch(UUID id) {
    jdbc.update("UPDATE viewing_appointments SET version=version+1 WHERE id=?", id);
  }

  public void state(UUID id, String status) {
    jdbc.update("UPDATE viewing_appointments SET status=? WHERE id=?", status, id);
  }

  public void confirm(UUID id, UUID proposal) {
    jdbc.update(
        "UPDATE viewing_appointments SET confirmed_proposal_id=?,status='CONFIRMED' WHERE id=?",
        proposal,
        id);
  }

  public void decline(ViewingAppointment a) {
    jdbc.update(
        "UPDATE viewing_appointments SET"
            + " current_proposal_id=coalesce(confirmed_proposal_id,current_proposal_id),status=CASE"
            + " WHEN confirmed_proposal_id IS NULL THEN 'DECLINED' ELSE 'CONFIRMED' END WHERE id=?",
        a.id());
  }

  public boolean overlap(UUID viewing, UUID unit, UUID customer, Instant start, Instant end) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS(SELECT 1 FROM viewing_appointments v JOIN conversations c ON c.id=v.conversation_id
            JOIN viewing_proposals q ON q.id=v.confirmed_proposal_id WHERE v.id<>? AND v.status IN ('CONFIRMED','RESCHEDULE_PENDING')
            AND (c.unit_id=? OR c.customer_id=?) AND q.start_at<? AND q.end_at>?)
            """,
            Boolean.class,
            viewing,
            unit,
            customer,
            Timestamp.from(end),
            Timestamp.from(start)));
  }

  public void history(
      UUID id,
      UUID proposal,
      UUID actor,
      ConversationRole role,
      String action,
      String reason,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " viewing_history(id,viewing_id,proposal_id,actor_id,role,action,reason,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        proposal,
        actor,
        role.name(),
        action,
        reason.trim(),
        Timestamp.from(now));
  }

  public List<ViewingHistoryResponse> history(UUID viewing, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM viewing_history WHERE viewing_id=? ORDER BY created_at DESC,id LIMIT ?"
            + " OFFSET ?",
        (r, n) ->
            new ViewingHistoryResponse(
                id(r, "id"),
                r.getString("action"),
                ConversationRole.valueOf(r.getString("role")),
                id(r, "proposal_id"),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant()),
        viewing,
        limit,
        offset);
  }

  private static ViewingProposalResponse projection(ResultSet r, String p) throws SQLException {
    return r.getObject(p + "id") == null
        ? null
        : new ViewingProposalResponse(
            id(r, p + "id"),
            ConversationRole.valueOf(r.getString(p + "role")),
            r.getTimestamp(p + "start").toInstant(),
            r.getTimestamp(p + "end").toInstant(),
            r.getLong(p + "listing_version"),
            r.getString(p + "instructions"),
            r.getString(p + "reason"),
            r.getTimestamp(p + "created").toInstant());
  }

  private static final String SELECT =
      "SELECT v.*,q.id AS q_id,q.proposed_role AS q_role,q.start_at AS q_start,q.end_at AS"
          + " q_end,q.listing_version AS q_listing_version,q.meeting_instructions AS"
          + " q_instructions,q.reason AS q_reason,q.created_at AS q_created,s.id AS"
          + " s_id,s.proposed_role AS s_role,s.start_at AS s_start,s.end_at AS"
          + " s_end,s.listing_version AS s_listing_version,s.meeting_instructions AS"
          + " s_instructions,s.reason AS s_reason,s.created_at AS s_created ";
  private static final String PROPOSALS =
      " JOIN viewing_appointments v ON v.conversation_id=c.id JOIN viewing_proposals q ON"
          + " q.id=v.current_proposal_id LEFT JOIN viewing_proposals s ON"
          + " s.id=v.confirmed_proposal_id ";
  private static final RowMapper<ViewingResponse> RESPONSE =
      (r, n) ->
          new ViewingResponse(
              id(r, "id"),
              id(r, "conversation_id"),
              r.getString("status"),
              r.getLong("version"),
              id(r, "current_proposal_id"),
              id(r, "confirmed_proposal_id"),
              projection(r, "q_"),
              projection(r, "s_"),
              r.getTimestamp("created_at").toInstant());

  public Optional<ViewingResponse> response(UUID actor, UUID conversation, UUID id) {
    return named
        .query(
            SELECT
                + ConversationRepository.FROM
                + PROPOSALS
                + " WHERE "
                + ConversationRepository.ALLOW
                + " AND c.id=:conversation AND v.id=:id",
            Map.of("actor", actor, "conversation", conversation, "id", id),
            RESPONSE)
        .stream()
        .findFirst();
  }

  public List<ViewingResponse> list(
      UUID actor,
      UUID org,
      UUID property,
      UUID conversation,
      String status,
      Instant from,
      Instant to,
      int limit,
      int offset) {
    var args = new HashMap<String, Object>();
    args.put("actor", actor);
    args.put("limit", limit);
    args.put("offset", offset);
    String where = " WHERE " + ConversationRepository.ALLOW;
    if (org != null) {
      where += " AND :org IN(p.owner_organization_id,p.manager_organization_id)";
      args.put("org", org);
    }
    if (property != null) {
      where += " AND c.property_id=:property";
      args.put("property", property);
    }
    if (conversation != null) {
      where += " AND c.id=:conversation";
      args.put("conversation", conversation);
    }
    if (status != null) {
      where += " AND v.status=:status";
      args.put("status", status);
    }
    if (from != null) {
      where +=
          " AND ((q.start_at<:to AND q.end_at>:from) OR (v.status IN"
              + " ('CONFIRMED','RESCHEDULE_PENDING') AND s.start_at<:to AND s.end_at>:from))";
      args.put("from", Timestamp.from(from));
      args.put("to", Timestamp.from(to));
    }
    return named.query(
        SELECT
            + ConversationRepository.FROM
            + PROPOSALS
            + where
            + " ORDER BY q.start_at,v.id LIMIT :limit OFFSET :offset",
        args,
        RESPONSE);
  }
}
