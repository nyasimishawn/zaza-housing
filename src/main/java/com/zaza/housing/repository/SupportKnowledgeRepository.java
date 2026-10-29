package com.zaza.housing.repository;

import com.zaza.housing.dto.SupportKnowledgeRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SupportKnowledgeRepository {
  private final JdbcTemplate jdbc;

  public SupportKnowledgeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(boolean admin, String q, int l, int f) {
    return jdbc.queryForList(
        "SELECT id,slug,title,body,status,version,updated_at AS \"updatedAt\" FROM"
            + " support_knowledge WHERE (? OR status='PUBLISHED') AND (?='' OR"
            + " strpos(lower(title||' '||body),lower(?))>0) ORDER BY title,id LIMIT ? OFFSET ?",
        admin,
        q,
        q,
        l,
        f);
  }

  public Map<String, Object> get(UUID id, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT * FROM support_knowledge WHERE id=?" + (lock ? " FOR UPDATE" : ""), id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public UUID create(UUID actor, SupportKnowledgeRequest r, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO support_knowledge(id,slug,title,body,status,updated_by,created_at,updated_at)"
            + " VALUES(?,?,?,?,'DRAFT',?,?,?)",
        id,
        r.slug(),
        r.title().strip(),
        r.body().strip(),
        actor,
        Timestamp.from(now),
        Timestamp.from(now));
    return id;
  }

  public void update(UUID id, UUID actor, SupportKnowledgeRequest r, Instant now) {
    jdbc.update(
        "UPDATE support_knowledge SET"
            + " title=?,body=?,status='DRAFT',version=version+1,updated_by=?,updated_at=? WHERE"
            + " id=?",
        r.title().strip(),
        r.body().strip(),
        actor,
        Timestamp.from(now),
        id);
  }

  public void state(UUID id, UUID actor, String status, Instant now) {
    jdbc.update(
        "UPDATE support_knowledge SET status=?,version=version+1,updated_by=?,updated_at=? WHERE"
            + " id=?",
        status,
        actor,
        Timestamp.from(now),
        id);
  }
}
