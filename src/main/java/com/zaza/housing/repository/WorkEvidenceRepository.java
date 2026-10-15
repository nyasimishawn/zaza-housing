package com.zaza.housing.repository;

import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.ProcessedImage;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.model.WorkEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WorkEvidenceRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<WorkEvidence> IMAGE =
      (r, n) ->
          new WorkEvidence(
              r.getObject("id", UUID.class),
              r.getObject("work_id", UUID.class),
              r.getObject("assignment_id", UUID.class),
              r.getObject("provider_id", UUID.class),
              WorkParty.valueOf(r.getString("party")),
              WorkVisibility.valueOf(r.getString("visibility")),
              r.getString("caption"),
              r.getString("storage_key"),
              r.getString("content_type"),
              r.getInt("width"),
              r.getInt("height"),
              r.getLong("byte_size"),
              r.getTimestamp("created_at").toInstant());

  public WorkEvidenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long count(UUID work) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM work_evidence WHERE work_id=?", Long.class, work);
  }

  public UUID create(
      WorkAccess access,
      UUID actor,
      WorkVisibility visibility,
      String caption,
      String key,
      ProcessedImage image) {
    var w = access.work();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " work_evidence(id,work_id,assignment_id,provider_id,actor_id,party,visibility,caption,storage_key,content_type,width,height,byte_size)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        w.id(),
        w.assignmentId(),
        w.providerId(),
        actor,
        access.party().name(),
        visibility.name(),
        caption,
        key,
        image.contentType(),
        image.width(),
        image.height(),
        image.bytes().length);
    return id;
  }

  public Optional<WorkEvidence> find(UUID work, UUID id) {
    return jdbc
        .query("SELECT * FROM work_evidence WHERE work_id=? AND id=?", IMAGE, work, id)
        .stream()
        .findFirst();
  }

  public List<WorkEvidence> list(WorkAccess access, int limit, int offset) {
    String where = access.party() == WorkParty.COORDINATOR ? "" : " AND visibility='SHARED'";
    var args = new ArrayList<Object>();
    args.add(access.work().id());
    if (access.party() == WorkParty.PROVIDER) {
      where += " AND (assignment_id IS NULL OR assignment_id=?)";
      args.add(access.work().assignmentId());
    }
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM work_evidence WHERE work_id=?"
            + where
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        IMAGE,
        args.toArray());
  }
}
