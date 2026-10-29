package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SavedDiscoveryRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public SavedDiscoveryRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private String table(String kind) {
    return switch (kind) {
      case "items" -> "discovery_saved_items";
      case "searches" -> "discovery_saved_searches";
      case "comparisons" -> "discovery_comparisons";
      case "alerts" -> "discovery_alerts";
      default -> throw new IllegalArgumentException();
    };
  }

  public boolean actor(UUID user, String token, Instant now, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT id,active FROM app_users WHERE id=?" + (lock ? " FOR UPDATE" : ""), user);
    return !rows.isEmpty()
        && Boolean.TRUE.equals(rows.get(0).get("active"))
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE user_id=? AND token_hash=? AND"
                    + " expires_at>?)",
                Boolean.class,
                user,
                token,
                Timestamp.from(now)));
  }

  public Map<String, Object> command(UUID request) {
    return jdbc
        .queryForList("SELECT * FROM discovery_commands WHERE request_id=?", request)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void command(
      UUID request, UUID user, String action, UUID id, String fingerprint, Instant now) {
    jdbc.update(
        "INSERT INTO discovery_commands VALUES(?,?,?,?,?,?)",
        request,
        user,
        action,
        id,
        fingerprint,
        Timestamp.from(now));
  }

  public List<Map<String, Object>> list(
      UUID user, String kind, int limit, int offset, String filter) {
    String extra = "";
    var args = new ArrayList<Object>();
    args.add(user);
    if (kind.equals("items") && filter != null) {
      extra = " AND folder=?";
      args.add(filter);
    }
    args.add(limit);
    args.add(offset);
    return jdbc.queryForList(
        "SELECT * FROM "
            + table(kind)
            + " WHERE user_id=?"
            + extra
            + " ORDER BY "
            + (kind.equals("alerts") ? "created_at" : "updated_at")
            + " DESC,id LIMIT ? OFFSET ?",
        args.toArray());
  }

  public Map<String, Object> get(UUID user, String kind, UUID id, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT * FROM "
                + table(kind)
                + " WHERE user_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            user,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public long count(UUID user, String kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table(kind) + " WHERE user_id=?", Long.class, user);
  }

  public void delete(UUID user, String kind, UUID id) {
    jdbc.update("DELETE FROM " + table(kind) + " WHERE user_id=? AND id=?", user, id);
  }

  public UUID existingItem(UUID user, String kind, UUID target) {
    return jdbc
        .query(
            "SELECT id FROM discovery_saved_items WHERE user_id=? AND kind=? AND target_id=?",
            (r, n) -> r.getObject(1, UUID.class),
            user,
            kind,
            target)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void item(UUID id, UUID user, DiscoveryCommandRequest r, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " discovery_saved_items(id,user_id,kind,target_id,folder,note,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        r.kind(),
        r.targetId(),
        r.folder() == null ? "" : r.folder().strip(),
        r.note() == null ? "" : r.note().strip(),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void editItem(UUID id, String folder, String note, Instant now) {
    jdbc.update(
        "UPDATE discovery_saved_items SET folder=?,note=?,version=version+1,updated_at=? WHERE"
            + " id=?",
        folder == null ? "" : folder.strip(),
        note == null ? "" : note.strip(),
        Timestamp.from(now),
        id);
  }

  public void search(
      UUID id,
      UUID user,
      String name,
      DiscoveryQuery q,
      String frequency,
      UUID generation,
      Instant next,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " discovery_saved_searches(id,user_id,name,query,frequency,generation,next_scan_at,created_at,updated_at)"
            + " VALUES(?,?,?,?::jsonb,?,?,?,?,?)",
        id,
        user,
        name,
        json.writeValueAsString(q),
        frequency,
        generation,
        Timestamp.from(next),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void editSearch(
      UUID id,
      String name,
      DiscoveryQuery q,
      String frequency,
      UUID generation,
      Instant next,
      Instant now,
      boolean reset) {
    jdbc.update(
        "UPDATE discovery_saved_searches SET"
            + " name=?,query=?::jsonb,frequency=?,generation=?,version=version+1,next_scan_at=?,last_error=NULL,updated_at=?"
            + " WHERE id=?",
        name,
        json.writeValueAsString(q),
        frequency,
        generation,
        Timestamp.from(next),
        Timestamp.from(now),
        id);
    if (reset) {
      jdbc.update("DELETE FROM discovery_alerts WHERE search_id=?", id);
      jdbc.update("DELETE FROM discovery_search_seen WHERE search_id=?", id);
    }
  }

  public void comparison(UUID id, UUID user, String name, List<UUID> ids, Instant now) {
    jdbc.update(
        "INSERT INTO discovery_comparisons(id,user_id,name,listing_ids,created_at,updated_at)"
            + " VALUES(?,?,?,?::jsonb,?,?)",
        id,
        user,
        name,
        json.writeValueAsString(ids),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void editComparison(UUID id, String name, List<UUID> ids, Instant now) {
    jdbc.update(
        "UPDATE discovery_comparisons SET"
            + " name=?,listing_ids=?::jsonb,version=version+1,updated_at=? WHERE id=?",
        name,
        json.writeValueAsString(ids),
        Timestamp.from(now),
        id);
  }

  public void read(UUID user, UUID id, Instant now) {
    jdbc.update(
        "UPDATE discovery_alerts SET read_at=COALESCE(read_at,?) WHERE user_id=? AND id=?",
        Timestamp.from(now),
        user,
        id);
  }

  public List<UUID> due(Instant now) {
    return jdbc.query(
        "SELECT s.id FROM discovery_saved_searches s JOIN app_users u ON u.id=s.user_id AND"
            + " u.active WHERE frequency<>'OFF' AND next_scan_at<=? ORDER BY next_scan_at,s.id"
            + " LIMIT 20",
        (r, n) -> r.getObject(1, UUID.class),
        Timestamp.from(now));
  }

  public Map<String, Object> lockDue(UUID id, Instant now) {
    return jdbc
        .queryForList(
            "SELECT s.* FROM discovery_saved_searches s JOIN app_users u ON u.id=s.user_id AND"
                + " u.active WHERE s.id=? AND s.frequency<>'OFF' AND s.next_scan_at<=? FOR UPDATE"
                + " OF s SKIP LOCKED",
            id,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void alert(UUID id, UUID user, UUID generation, UUID target, Instant now) {
    jdbc.update(
        "INSERT INTO discovery_search_seen(search_id,generation,target_id) VALUES(?,?,?) ON"
            + " CONFLICT DO NOTHING",
        id,
        generation,
        target);
    jdbc.update(
        "INSERT INTO discovery_alerts(id,user_id,search_id,generation,target_id,created_at)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID(),
        user,
        id,
        generation,
        target,
        Timestamp.from(now));
  }

  public void scanned(UUID id, Instant next, Instant now) {
    jdbc.update(
        "UPDATE discovery_saved_searches SET last_scan_at=?,next_scan_at=?,last_error=NULL WHERE"
            + " id=?",
        Timestamp.from(now),
        Timestamp.from(next),
        id);
  }

  public void failed(UUID id, Instant next) {
    jdbc.update(
        "UPDATE discovery_saved_searches SET next_scan_at=?,last_error='Matching temporarily"
            + " unavailable; a retry is scheduled.' WHERE id=?",
        Timestamp.from(next),
        id);
  }
}
