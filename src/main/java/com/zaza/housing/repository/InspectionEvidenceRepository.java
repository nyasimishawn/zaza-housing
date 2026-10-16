package com.zaza.housing.repository;

import com.zaza.housing.model.InspectionEvidence;
import com.zaza.housing.model.ProcessedImage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InspectionEvidenceRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<InspectionEvidence> ROW =
      (r, n) ->
          new InspectionEvidence(
              r.getObject("id", UUID.class),
              r.getObject("inspection_id", UUID.class),
              r.getObject("uploader_id", UUID.class),
              r.getString("party"),
              r.getString("caption"),
              r.getString("storage_key"),
              r.getString("content_type"),
              r.getInt("width"),
              r.getInt("height"),
              r.getLong("byte_size"),
              r.getTimestamp("created_at").toInstant());
  private static final String VISIBLE =
      " AND (e.party='TENANT' OR EXISTS(SELECT 1 FROM lease_inspection_evidence_publications p"
          + " WHERE p.inspection_id=e.inspection_id AND p.evidence_id=e.id))";

  public InspectionEvidenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int count(UUID inspection) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM lease_inspection_evidence WHERE inspection_id=?",
        Integer.class,
        inspection);
  }

  public UUID create(
      UUID inspection,
      UUID actor,
      String party,
      String caption,
      String key,
      ProcessedImage image,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " lease_inspection_evidence(id,inspection_id,uploader_id,party,caption,storage_key,content_type,width,height,byte_size,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id,
        inspection,
        actor,
        party,
        caption,
        key,
        image.contentType(),
        image.width(),
        image.height(),
        image.bytes().length,
        java.sql.Timestamp.from(now));
    return id;
  }

  public Optional<InspectionEvidence> find(UUID inspection, UUID image, boolean tenant) {
    return jdbc
        .query(
            "SELECT e.* FROM lease_inspection_evidence e WHERE e.inspection_id=? AND e.id=?"
                + (tenant ? VISIBLE : ""),
            ROW,
            inspection,
            image)
        .stream()
        .findFirst();
  }

  public List<InspectionEvidence> list(UUID inspection, boolean tenant, int limit, int offset) {
    return jdbc.query(
        "SELECT e.* FROM lease_inspection_evidence e WHERE e.inspection_id=?"
            + (tenant ? VISIBLE : "")
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        ROW,
        inspection,
        limit,
        offset);
  }
}
