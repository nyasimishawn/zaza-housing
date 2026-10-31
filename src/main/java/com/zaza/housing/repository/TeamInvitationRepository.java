package com.zaza.housing.repository;

import com.zaza.housing.dto.TeamInvitationEventResponse;
import com.zaza.housing.model.TeamInvitation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class TeamInvitationRepository {
  private final JdbcTemplate jdbc;
  private static final String SELECT =
      "SELECT i.*,o.name organization_name,(SELECT d.status FROM notification_deliveries d WHERE"
          + " d.invitation_id=i.id ORDER BY d.created_at DESC,d.id LIMIT 1) delivery_status FROM"
          + " team_invitations i JOIN organizations o ON o.id=i.organization_id ";
  private static final RowMapper<TeamInvitation> ROW =
      (r, n) ->
          new TeamInvitation(
              r.getObject("id", UUID.class),
              r.getObject("organization_id", UUID.class),
              r.getString("organization_name"),
              r.getString("email"),
              r.getString("role"),
              r.getObject("invited_by", UUID.class),
              r.getObject("request_id", UUID.class),
              r.getString("token_hash"),
              r.getString("status"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant(),
              r.getTimestamp("expires_at").toInstant(),
              r.getTimestamp("last_sent_at").toInstant(),
              r.getInt("send_count"),
              r.getString("delivery_status"));

  public TeamInvitationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TeamInvitation> find(UUID id, boolean lock) {
    return jdbc.query(SELECT + "WHERE i.id=?" + (lock ? " FOR UPDATE OF i" : ""), ROW, id).stream()
        .findFirst();
  }

  public Optional<TeamInvitation> request(UUID org, UUID request) {
    return jdbc
        .query(SELECT + "WHERE i.organization_id=? AND i.request_id=?", ROW, org, request)
        .stream()
        .findFirst();
  }

  public Optional<TeamInvitation> token(String hash) {
    return jdbc.query(SELECT + "WHERE i.token_hash=?", ROW, hash).stream().findFirst();
  }

  public List<TeamInvitation> list(UUID org, String email, String status, int limit, int offset) {
    return jdbc.query(
        SELECT
            + "WHERE "
            + (org == null ? "i.email=?" : "i.organization_id=?")
            + (status == null
                ? ""
                : " AND (CASE WHEN i.status='PENDING' AND i.expires_at<=now() THEN 'EXPIRED' ELSE"
                    + " i.status END)=?")
            + " ORDER BY i.created_at DESC,i.id LIMIT ? OFFSET ?",
        ROW,
        status == null
            ? new Object[] {org == null ? email : org, limit, offset}
            : new Object[] {org == null ? email : org, status, limit, offset});
  }

  public void create(
      UUID id,
      UUID org,
      String email,
      String role,
      UUID actor,
      UUID request,
      String hash,
      Instant now,
      Instant expires) {
    jdbc.update(
        "INSERT INTO"
            + " team_invitations(id,organization_id,email,role,invited_by,request_id,token_hash,created_at,updated_at,expires_at,last_sent_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        org,
        email,
        role,
        actor,
        request,
        hash,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(expires),
        Timestamp.from(now));
  }

  public void expire(UUID org, String email, Instant now) {
    jdbc.update(
        "UPDATE team_invitations SET status='EXPIRED',version=version+1,updated_at=? WHERE"
            + " organization_id=? AND email=? AND status='PENDING' AND expires_at<=?",
        Timestamp.from(now),
        org,
        email,
        Timestamp.from(now));
  }

  public boolean pending(UUID org, String email) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM team_invitations WHERE organization_id=? AND email=? AND"
            + " status='PENDING')",
        Boolean.class,
        org,
        email);
  }

  public int recent(UUID org, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM team_invitations WHERE organization_id=? AND created_at>=?",
        Integer.class,
        org,
        Timestamp.from(since));
  }

  public void state(UUID id, String status, UUID accepted, Instant now) {
    jdbc.update(
        "UPDATE team_invitations SET status=?,accepted_by=?,updated_at=?,version=version+1 WHERE"
            + " id=?",
        status,
        accepted,
        Timestamp.from(now),
        id);
  }

  public void rotate(UUID id, String hash, Instant now, Instant until) {
    jdbc.update(
        "UPDATE team_invitations SET"
            + " token_hash=?,last_sent_at=?,updated_at=?,expires_at=?,send_count=send_count+1,version=version+1"
            + " WHERE id=?",
        hash,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(until),
        id);
  }

  public void event(UUID id, UUID actor, String action, Instant now) {
    jdbc.update(
        "INSERT INTO team_invitation_events(id,invitation_id,actor_id,action,created_at)"
            + " VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        actor,
        action,
        Timestamp.from(now));
  }

  public List<TeamInvitationEventResponse> history(UUID id, int limit, int offset) {
    return jdbc.query(
        "SELECT id,action,created_at FROM team_invitation_events WHERE invitation_id=? ORDER BY"
            + " created_at DESC,id LIMIT ? OFFSET ?",
        (r, n) ->
            new TeamInvitationEventResponse(
                r.getObject("id", UUID.class),
                r.getString("action"),
                r.getTimestamp("created_at").toInstant()),
        id,
        limit,
        offset);
  }

  public boolean verified(UUID user) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM app_users WHERE id=? AND active AND email_verified_at IS NOT"
            + " NULL)",
        Boolean.class,
        user);
  }

  public void queue(
      UUID id, UUID invitation, String encrypted, Instant now, Instant until, int generation) {
    jdbc.update(
        "UPDATE notification_deliveries SET status='CANCELLED',encrypted_payload=NULL WHERE"
            + " invitation_id=? AND status='PENDING'",
        invitation);
    jdbc.update(
        "INSERT INTO"
            + " notification_deliveries(id,invitation_id,channel,purpose,category,encrypted_payload,dedupe_key,available_at,expires_at,created_at)"
            + " VALUES(?,?,'EMAIL','INVITATION','TEAM',?,?,?,?,?)",
        id,
        invitation,
        encrypted,
        "invite:" + invitation + ":" + generation,
        Timestamp.from(now),
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public void cancelDelivery(UUID invitation) {
    jdbc.update(
        "UPDATE notification_deliveries SET status='CANCELLED',encrypted_payload=NULL WHERE"
            + " invitation_id=? AND status='PENDING'",
        invitation);
  }
}
