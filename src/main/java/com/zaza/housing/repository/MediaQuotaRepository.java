package com.zaza.housing.repository;

import com.zaza.housing.model.RetainedMediaUsage;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MediaQuotaRepository {
  private final JdbcTemplate jdbc;

  public MediaQuotaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public void lock(String scope) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        (rs, row) -> 0, "media:" + scope);
  }

  public RetainedMediaUsage usage(UUID scope, boolean organization) {
    String predicate = organization ? "p.owner_organization_id = ?" : "m.created_by = ?";
    return jdbc.queryForObject("""
        SELECT COALESCE(sum(byte_size), 0)::bigint AS bytes, count(*) AS objects,
          count(*) FILTER (WHERE created_at >= now() - interval '24 hours') AS recent
        FROM (
          SELECT m.byte_size, m.created_at FROM property_images m
          JOIN properties p ON p.id = m.property_id WHERE %s
          UNION ALL
          SELECT m.byte_size, m.created_at FROM property_videos m
          JOIN properties p ON p.id = m.property_id WHERE %s
        ) retained
        """.formatted(predicate, predicate),
        (rs, row) -> new RetainedMediaUsage(rs.getLong("bytes"), rs.getLong("objects"), rs.getLong("recent")),
        scope, scope);
  }
}
