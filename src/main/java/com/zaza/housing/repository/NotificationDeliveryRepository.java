package com.zaza.housing.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDeliveryRepository {
  private final JdbcTemplate jdbc;

  public NotificationDeliveryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> next(Instant now) {
    return jdbc
        .queryForList(
            """
            SELECT d.*,u.email,u.active user_active,u.email_verified_at,i.email invitation_email,i.status invitation_status,
            i.expires_at invitation_expires,i.token_hash invitation_token_hash,
            p.encrypted_phone,p.phone_hash,p.verified_at phone_verified,p.expires_at phone_expires,
            v.encrypted_token,(v.active AND EXISTS(SELECT 1 FROM auth_sessions a WHERE a.id=v.session_id AND a.user_id=d.user_id AND a.expires_at>now())) device_active,s.timezone,s.quiet_start,s.quiet_end,
            coalesce(f.email,FALSE) email_enabled,coalesce(f.sms,FALSE) sms_enabled,coalesce(f.push,FALSE) push_enabled,
            EXISTS(SELECT 1 FROM notification_preferences enabled WHERE enabled.user_id=d.user_id AND enabled.email) email_any_enabled
            FROM notification_deliveries d
            LEFT JOIN app_users u ON u.id=d.user_id LEFT JOIN team_invitations i ON i.id=d.invitation_id
            LEFT JOIN notification_phone_contacts p ON p.user_id=d.user_id
            LEFT JOIN notification_devices v ON v.id=d.device_id AND v.user_id=d.user_id
            LEFT JOIN notification_settings s ON s.user_id=d.user_id
            LEFT JOIN notification_preferences f ON f.user_id=d.user_id AND f.category=d.category
            WHERE d.status='PENDING' AND d.available_at<=? ORDER BY d.available_at,d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
            """,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public void postpone(UUID id, Instant due, String error) {
    jdbc.update(
        "UPDATE notification_deliveries SET available_at=?,error_code=? WHERE id=?",
        Timestamp.from(due),
        error,
        id);
  }

  public void finish(
      UUID id,
      String status,
      int attempts,
      Instant now,
      Instant next,
      String error,
      String reference) {
    jdbc.update(
        "UPDATE notification_deliveries SET"
            + " status=?,attempts=?,last_attempt_at=?,available_at=?,error_code=?,provider_reference=?,encrypted_payload=CASE"
            + " WHEN ? IN('ACCEPTED','CANCELLED','EXPIRED') THEN NULL ELSE encrypted_payload END"
            + " WHERE id=?",
        status,
        attempts,
        Timestamp.from(now),
        Timestamp.from(next),
        error,
        reference,
        status,
        id);
  }

  public void attempt(UUID id, int attempt, String outcome, String error, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " notification_delivery_attempts(id,delivery_id,attempt,outcome,error_code,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        attempt,
        outcome,
        error,
        Timestamp.from(now));
  }

  public void invalidDevice(UUID id) {
    jdbc.update("UPDATE notification_devices SET active=FALSE WHERE id=?", id);
  }

  public List<UUID> dueIds(Instant now) {
    return jdbc.query(
        "SELECT id FROM notification_deliveries WHERE status='PENDING' AND available_at<=? ORDER BY"
            + " available_at,id LIMIT 20",
        (r, n) -> r.getObject(1, UUID.class),
        Timestamp.from(now));
  }

  public Map<String, Long> summary() {
    var result = new LinkedHashMap<String, Long>();
    jdbc.query(
        "SELECT status,count(*) total FROM notification_deliveries GROUP BY status",
        r -> {
          result.put(r.getString("status"), r.getLong("total"));
        });
    return result;
  }

  public List<com.zaza.housing.dto.NotificationChannelQueueResponse> queues() {
    return jdbc.query(
        "SELECT channel,status,count(*) total,min(created_at) oldest FROM notification_deliveries"
            + " GROUP BY channel,status ORDER BY channel,status",
        (r, n) ->
            new com.zaza.housing.dto.NotificationChannelQueueResponse(
                r.getString("channel"),
                r.getString("status"),
                r.getLong("total"),
                r.getTimestamp("oldest").toInstant()));
  }
}
