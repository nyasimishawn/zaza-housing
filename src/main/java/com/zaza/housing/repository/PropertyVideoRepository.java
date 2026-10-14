package com.zaza.housing.repository;

import com.zaza.housing.enums.VideoStatus;
import com.zaza.housing.model.ProcessedVideo;
import com.zaza.housing.model.PropertyVideo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyVideoRepository {
    private static final RowMapper<PropertyVideo> VIDEO = (rs, row) -> new PropertyVideo(
            rs.getObject("id", UUID.class), rs.getObject("property_id", UUID.class), rs.getString("storage_key"),
            rs.getString("content_type"), rs.getInt("width"), rs.getInt("height"), rs.getDouble("duration_seconds"), rs.getLong("byte_size"),
            rs.getString("caption"), VideoStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    public PropertyVideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID create(UUID property, UUID actor, String key, ProcessedVideo video, String caption) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_videos(id, property_id, created_by, storage_key, content_type, width, height, duration_seconds, byte_size, caption)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, property, actor, key, "video/mp4", video.width(), video.height(), video.durationSeconds(), video.byteSize(), caption);
        return id;
    }
    public Optional<PropertyVideo> find(UUID property, UUID id) {
        return jdbc.query("SELECT * FROM property_videos WHERE property_id = ? AND id = ?", VIDEO, property, id).stream().findFirst();
    }
    public List<PropertyVideo> list(UUID property, boolean includeArchived, int limit, int offset) {
        return jdbc.query("SELECT * FROM property_videos WHERE property_id = ?" + (includeArchived ? "" : " AND status = 'ACTIVE'")
                + " ORDER BY created_at, id LIMIT ? OFFSET ?", VIDEO, property, limit, offset);
    }
    public int activeCount(UUID property) {
        return jdbc.queryForObject("SELECT count(*) FROM property_videos WHERE property_id = ? AND status = 'ACTIVE'", Integer.class, property);
    }
    public Optional<PropertyVideo> publishedReference(UUID listing, UUID video) {
        return jdbc.query("""
                SELECT v.* FROM property_videos v JOIN listing_video_references r ON r.video_id = v.id AND r.property_id = v.property_id
                WHERE r.listing_id = ? AND v.id = ? AND v.status = 'ACTIVE' AND v.version = r.video_version
                """, VIDEO, listing, video).stream().findFirst();
    }
    public void update(UUID property, UUID id, String caption) {
        jdbc.update("UPDATE property_videos SET caption = ?, version = version + 1, updated_at = clock_timestamp() WHERE property_id = ? AND id = ?", caption, property, id);
    }
    public void changeStatus(UUID property, UUID id, VideoStatus status) {
        jdbc.update("UPDATE property_videos SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE property_id = ? AND id = ?", status.name(), property, id);
    }
}
