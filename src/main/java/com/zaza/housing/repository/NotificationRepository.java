package com.zaza.housing.repository;

import com.zaza.housing.dto.NotificationResponse;
import com.zaza.housing.enums.ApplicationEventType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private static final RowMapper<NotificationResponse> NOTIFICATION = (rs, row) -> new NotificationResponse(
            rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class), ApplicationEventType.valueOf(rs.getString("type")),
            rs.getString("message"), "/api/v1/me/applications/" + rs.getObject("application_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant());
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void append(UUID user, UUID application, UUID event, ApplicationEventType type, String message) {
        jdbc.update("INSERT INTO in_app_notifications(id, user_id, application_id, event_id, type, message) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, application, event, type.name(), message);
    }
    public List<NotificationResponse> list(UUID user, boolean unreadOnly, int limit, int offset) {
        return jdbc.query("SELECT * FROM in_app_notifications WHERE user_id = ?" + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", NOTIFICATION, user, limit, offset);
    }
    public long unread(UUID user) { return jdbc.queryForObject("SELECT count(*) FROM in_app_notifications WHERE user_id = ? AND read_at IS NULL", Long.class, user); }
    public Optional<NotificationResponse> markRead(UUID user, UUID id) {
        return jdbc.query("UPDATE in_app_notifications SET read_at = COALESCE(read_at, clock_timestamp()) WHERE user_id = ? AND id = ? RETURNING *", NOTIFICATION, user, id).stream().findFirst();
    }
}
