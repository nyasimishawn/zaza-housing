package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.NotificationCategory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationWorkspaceRepository {
  private final JdbcTemplate jdbc;

  public NotificationWorkspaceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockUser(UUID user) {
    jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", user);
  }

  public Map<String, Object> settings(UUID user) {
    jdbc.update(
        "INSERT INTO notification_settings(user_id) VALUES(?) ON CONFLICT DO NOTHING", user);
    return jdbc.queryForMap("SELECT * FROM notification_settings WHERE user_id=?", user);
  }

  public List<NotificationCategoryPreference> preferences(UUID user) {
    var rows =
        jdbc.query(
            "SELECT * FROM notification_preferences WHERE user_id=?",
            (r, n) ->
                new NotificationCategoryPreference(
                    NotificationCategory.valueOf(r.getString("category")),
                    r.getBoolean("in_app"),
                    r.getBoolean("email"),
                    r.getBoolean("sms"),
                    r.getBoolean("push")),
            user);
    var map =
        new EnumMap<NotificationCategory, NotificationCategoryPreference>(
            NotificationCategory.class);
    for (var row : rows) map.put(row.category(), row);
    return Arrays.stream(NotificationCategory.values())
        .map(
            c ->
                map.getOrDefault(
                    c, new NotificationCategoryPreference(c, true, false, false, false)))
        .toList();
  }

  public void settings(UUID user, NotificationSettingsRequest r) {
    jdbc.update(
        "UPDATE notification_settings SET"
            + " version=version+1,timezone=?,quiet_start=?,quiet_end=?,email_mode=? WHERE"
            + " user_id=?",
        r.timezone(),
        r.quietStart(),
        r.quietEnd(),
        r.emailMode(),
        user);
    for (var p : r.categories())
      jdbc.update(
          "INSERT INTO notification_preferences(user_id,category,in_app,email,sms,push)"
              + " VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,category) DO UPDATE SET"
              + " in_app=excluded.in_app,email=excluded.email,sms=excluded.sms,push=excluded.push",
          user,
          p.category().name(),
          p.inApp(),
          p.email(),
          p.sms(),
          p.push());
  }

  public boolean verified(UUID user) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM app_users WHERE id=? AND active AND email_verified_at IS NOT"
            + " NULL)",
        Boolean.class,
        user);
  }

  public Optional<Map<String, Object>> phone(UUID user) {
    return jdbc
        .queryForList("SELECT * FROM notification_phone_contacts WHERE user_id=?", user)
        .stream()
        .findFirst();
  }

  public void phone(UUID user, String encrypted, String hash, String code, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " notification_phone_contacts(user_id,encrypted_phone,phone_hash,code_hash,expires_at,sent_at)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET"
            + " encrypted_phone=excluded.encrypted_phone,phone_hash=excluded.phone_hash,code_hash=excluded.code_hash,expires_at=excluded.expires_at,sent_at=excluded.sent_at,verified_at=NULL,attempts=0",
        user,
        encrypted,
        hash,
        code,
        Timestamp.from(now.plusSeconds(600)),
        Timestamp.from(now));
  }

  public void phoneAttempt(UUID user, boolean verified, Instant now) {
    jdbc.update(
        "UPDATE notification_phone_contacts SET attempts=attempts+1,verified_at=CASE WHEN ? THEN ?"
            + " ELSE verified_at END,expires_at=CASE WHEN ? THEN ? ELSE expires_at END WHERE"
            + " user_id=?",
        verified,
        Timestamp.from(now),
        verified,
        Timestamp.from(now),
        user);
  }

  public void removePhone(UUID user) {
    jdbc.update("DELETE FROM notification_phone_contacts WHERE user_id=?", user);
    jdbc.update("UPDATE notification_preferences SET sms=FALSE WHERE user_id=?", user);
    jdbc.update(
        "UPDATE notification_deliveries SET status='CANCELLED',encrypted_payload=NULL WHERE"
            + " user_id=? AND channel='SMS' AND status='PENDING'",
        user);
  }

  public int phoneChallenges(UUID user, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification_deliveries WHERE user_id=? AND purpose='PHONE_CODE' AND"
            + " created_at>=?",
        Integer.class,
        user,
        Timestamp.from(since));
  }

  public void cancelPhoneCodes(UUID user) {
    jdbc.update(
        "UPDATE notification_deliveries SET status='CANCELLED',encrypted_payload=NULL WHERE"
            + " user_id=? AND purpose='PHONE_CODE' AND status='PENDING'",
        user);
  }

  public List<NotificationDeviceResponse> devices(UUID user) {
    return jdbc.query(
        "SELECT * FROM notification_devices WHERE user_id=? ORDER BY updated_at DESC,id",
        (r, n) ->
            new NotificationDeviceResponse(
                r.getObject("id", UUID.class),
                r.getString("platform"),
                r.getString("label"),
                r.getBoolean("active"),
                r.getTimestamp("updated_at").toInstant()),
        user);
  }

  public Optional<Map<String, Object>> device(UUID user, UUID installation) {
    return jdbc
        .queryForList(
            "SELECT * FROM notification_devices WHERE user_id=? AND installation_id=?",
            user,
            installation)
        .stream()
        .findFirst();
  }

  public Optional<UUID> session(UUID user, String hash) {
    return jdbc
        .query(
            "SELECT id FROM auth_sessions WHERE user_id=? AND token_hash=? AND expires_at>now()",
            (r, n) -> r.getObject(1, UUID.class),
            user,
            hash)
        .stream()
        .findFirst();
  }

  public void device(
      UUID id,
      UUID user,
      UUID session,
      RegisterNotificationDeviceRequest r,
      String encrypted,
      String hash,
      Instant now) {
    // A token belongs to one signed-in account. Rebinding never reveals its previous owner.
    jdbc.update(
        "UPDATE notification_devices SET"
            + " active=FALSE,session_id=NULL,token_hash=encode(sha256((id::text||token_hash)::bytea),'hex')"
            + " WHERE token_hash=? AND id<>?",
        hash,
        id);
    jdbc.update(
        "INSERT INTO"
            + " notification_devices(id,user_id,installation_id,session_id,platform,label,encrypted_token,token_hash,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,installation_id) DO UPDATE SET"
            + " session_id=excluded.session_id,platform=excluded.platform,label=excluded.label,encrypted_token=excluded.encrypted_token,token_hash=excluded.token_hash,active=TRUE,updated_at=excluded.updated_at",
        id,
        user,
        r.installationId(),
        session,
        r.platform(),
        r.label().trim(),
        encrypted,
        hash,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public boolean revokeDevice(UUID user, UUID id, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE notification_devices SET active=FALSE,updated_at=? WHERE id=? AND user_id=?",
            Timestamp.from(now),
            id,
            user);
    jdbc.update(
        "UPDATE notification_deliveries SET status='CANCELLED' WHERE device_id=? AND user_id=? AND"
            + " status='PENDING'",
        id,
        user);
    return changed > 0;
  }

  public void event(
      UUID user,
      String kind,
      UUID target,
      String type,
      String message,
      String dedupe,
      Instant now) {
    UUID id = UUID.randomUUID();
    if (jdbc.update(
            "INSERT INTO"
                + " notification_workspace_events(id,user_id,kind,target_id,created_at,dedupe_key)"
                + " VALUES(?,?,?,?,?,?) ON CONFLICT(dedupe_key) DO NOTHING",
            id,
            user,
            kind,
            target,
            Timestamp.from(now),
            dedupe)
        == 0) return;
    jdbc.update(
        "INSERT INTO in_app_notifications(id,user_id,workspace_event_id,type,message)"
            + " VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        user,
        id,
        type,
        message);
  }

  public List<NotificationDeliveryResponse> deliveries(UUID user, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM notification_deliveries WHERE user_id=? ORDER BY created_at DESC,id LIMIT ?"
            + " OFFSET ?",
        (r, n) ->
            new NotificationDeliveryResponse(
                r.getObject("id", UUID.class),
                r.getObject("notification_id", UUID.class),
                r.getString("channel"),
                r.getString("purpose"),
                r.getString("category"),
                r.getString("status"),
                r.getInt("attempts"),
                r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("available_at").toInstant(),
                r.getString("error_code")),
        user,
        limit,
        offset);
  }

  public List<NotificationDeliveryAttemptResponse> attempts(UUID user, UUID id) {
    return jdbc.query(
        "SELECT a.* FROM notification_delivery_attempts a JOIN notification_deliveries d ON"
            + " d.id=a.delivery_id WHERE d.user_id=? AND d.id=? ORDER BY attempt",
        (r, n) ->
            new NotificationDeliveryAttemptResponse(
                r.getInt("attempt"),
                r.getString("outcome"),
                r.getString("error_code"),
                r.getTimestamp("created_at").toInstant()),
        user,
        id);
  }

  public boolean retry(UUID user, UUID id, Instant now) {
    return jdbc.update(
            "UPDATE notification_deliveries SET status='PENDING',available_at=?,error_code=NULL"
                + " WHERE user_id=? AND id=? AND purpose<>'PHONE_CODE' AND status='FAILED' AND"
                + " attempts<8 AND expires_at>? AND (last_attempt_at IS NULL OR last_attempt_at<?)",
            Timestamp.from(now),
            user,
            id,
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(60)))
        > 0;
  }

  public void payload(
      UUID id,
      UUID user,
      String channel,
      String purpose,
      String category,
      String encrypted,
      Instant now,
      Instant until) {
    jdbc.update(
        "INSERT INTO"
            + " notification_deliveries(id,user_id,channel,purpose,category,encrypted_payload,dedupe_key,available_at,expires_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        user,
        channel,
        purpose,
        category,
        encrypted,
        "payload:" + id,
        Timestamp.from(now),
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public int inbox(UUID user, UUID id, Instant before, String action) {
    String column = action.equals("archive") ? "archived_at" : "read_at";
    return jdbc.update(
        "UPDATE in_app_notifications SET "
            + column
            + "=coalesce("
            + column
            + ",now()) WHERE user_id=? AND "
            + column
            + " IS NULL"
            + (id != null ? " AND id=?" : " AND created_at<=?"),
        user,
        id != null ? id : Timestamp.from(before));
  }

  public int restore(UUID user, UUID id) {
    return jdbc.update(
        "UPDATE in_app_notifications SET archived_at=NULL WHERE user_id=? AND id=?", user, id);
  }

  public List<NotificationReminderResponse> reminders(UUID user, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM notification_reminders WHERE user_id=? ORDER BY due_at,id LIMIT ? OFFSET ?",
        REMINDER,
        user,
        limit,
        offset);
  }

  private static final RowMapper<NotificationReminderResponse> REMINDER =
      (r, n) ->
          new NotificationReminderResponse(
              r.getObject("id", UUID.class),
              r.getString("title"),
              r.getTimestamp("due_at").toInstant(),
              r.getString("repeat_rule"),
              r.getString("status"),
              r.getLong("version"),
              r.getTimestamp("expires_at").toInstant());

  public Optional<NotificationReminderResponse> reminder(UUID user, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM notification_reminders WHERE user_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            REMINDER,
            user,
            id)
        .stream()
        .findFirst();
  }

  public Optional<NotificationReminderResponse> reminderRequest(UUID user, UUID request) {
    return jdbc
        .query(
            "SELECT * FROM notification_reminders WHERE user_id=? AND request_id=?",
            REMINDER,
            user,
            request)
        .stream()
        .findFirst();
  }

  public int activeReminders(UUID user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification_reminders WHERE user_id=? AND status='ACTIVE'",
        Integer.class,
        user);
  }

  public void reminder(UUID id, UUID user, CreateNotificationReminderRequest r, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " notification_reminders(id,user_id,request_id,title,due_at,repeat_rule,created_at,expires_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        r.requestId(),
        r.title().trim(),
        Timestamp.from(r.dueAt()),
        r.repeatRule(),
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(15552000)));
  }

  public void updateReminder(UUID user, UUID id, UpdateNotificationReminderRequest r) {
    jdbc.update(
        "UPDATE notification_reminders SET title=?,due_at=?,repeat_rule=?,version=version+1 WHERE"
            + " id=? AND user_id=?",
        r.title().trim(),
        Timestamp.from(r.dueAt()),
        r.repeatRule(),
        id,
        user);
  }

  public void cancelReminder(UUID user, UUID id) {
    jdbc.update(
        "UPDATE notification_reminders SET status='CANCELLED',version=version+1 WHERE user_id=? AND"
            + " id=?",
        user,
        id);
  }

  public List<Map<String, Object>> dueReminders(Instant now) {
    return jdbc.queryForList(
        "SELECT r.* FROM notification_reminders r JOIN app_users u ON u.id=r.user_id AND u.active"
            + " WHERE r.status='ACTIVE' AND r.due_at<=? ORDER BY r.due_at,r.id LIMIT 50 FOR UPDATE"
            + " OF r SKIP LOCKED",
        Timestamp.from(now));
  }

  public void finishReminder(UUID id, Instant next) {
    jdbc.update(
        "UPDATE notification_reminders SET status=?,due_at=coalesce(?,due_at),version=version+1"
            + " WHERE id=?",
        next == null ? "COMPLETED" : "ACTIVE",
        next == null ? null : Timestamp.from(next),
        id);
  }
}
