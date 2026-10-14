package com.zaza.housing.repository;

import com.zaza.housing.enums.ImageStatus;
import com.zaza.housing.model.ProcessedImage;
import com.zaza.housing.model.PropertyImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyImageRepository {
    private static final RowMapper<PropertyImage> IMAGE = (rs, row) -> new PropertyImage(
            rs.getObject("id", UUID.class), rs.getObject("property_id", UUID.class), rs.getString("storage_key"),
            rs.getString("content_type"), rs.getInt("width"), rs.getInt("height"), rs.getLong("byte_size"),
            rs.getString("alt_text"), ImageStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    public PropertyImageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID create(UUID property, UUID actor, String key, ProcessedImage image, String altText) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_images(id, property_id, created_by, storage_key, content_type, width, height, byte_size, alt_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, property, actor, key, image.contentType(), image.width(), image.height(), image.bytes().length, altText);
        return id;
    }
    public Optional<PropertyImage> find(UUID property, UUID id) {
        return jdbc.query("SELECT * FROM property_images WHERE property_id = ? AND id = ?", IMAGE, property, id).stream().findFirst();
    }
    public List<PropertyImage> list(UUID property, boolean includeArchived, int limit, int offset) {
        return jdbc.query("SELECT * FROM property_images WHERE property_id = ?" + (includeArchived ? "" : " AND status = 'ACTIVE'")
                + " ORDER BY created_at, id LIMIT ? OFFSET ?", IMAGE, property, limit, offset);
    }
    public int activeCount(UUID property) {
        return jdbc.queryForObject("SELECT count(*) FROM property_images WHERE property_id = ? AND status = 'ACTIVE'", Integer.class, property);
    }
    public Optional<PropertyImage> publishedReference(UUID listing, UUID image) {
        return jdbc.query("""
                SELECT i.* FROM property_images i JOIN listing_image_references r ON r.image_id = i.id AND r.property_id = i.property_id
                WHERE r.listing_id = ? AND i.id = ? AND i.status = 'ACTIVE' AND i.version = r.image_version
                """, IMAGE, listing, image).stream().findFirst();
    }
    public void update(UUID property, UUID id, String altText) {
        jdbc.update("UPDATE property_images SET alt_text = ?, version = version + 1, updated_at = clock_timestamp() WHERE property_id = ? AND id = ?", altText, property, id);
    }
    public void changeStatus(UUID property, UUID id, ImageStatus status) {
        jdbc.update("UPDATE property_images SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE property_id = ? AND id = ?", status.name(), property, id);
    }
}
