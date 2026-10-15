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
            rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class), rs.getString("type"),
            rs.getString("message"), "/api/v1/me/applications/" + rs.getObject("application_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(), rs.getObject("lease_id", UUID.class), rs.getString("lease_url"));
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void append(UUID user, UUID application, UUID event, ApplicationEventType type, String message) {
        jdbc.update("INSERT INTO in_app_notifications(id, user_id, application_id, event_id, type, message) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, application, event, type.name(), message);
    }
    public void leaseTenant(UUID user,UUID application,UUID lease,UUID event,String type,String message) {
        leaseNotification(user,application,lease,event,type,message,"/api/v1/me/leases/"+lease);
    }
    private void leaseNotification(UUID user,UUID application,UUID lease,UUID event,String type,String message,String url) {
        jdbc.update("INSERT INTO in_app_notifications(id,user_id,application_id,lease_id,lease_event_id,type,message,lease_url) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),user,application,lease,event,type,message,url);
    }
    public void leaseManagers(UUID actor,UUID application,UUID lease,UUID property,UUID event,String type,String message) {
        jdbc.query("""
                SELECT DISTINCT ON (m.user_id) m.user_id,m.organization_id FROM organization_memberships m
                JOIN properties p ON p.id=? AND (m.organization_id=p.owner_organization_id OR m.organization_id=p.manager_organization_id)
                JOIN app_users u ON u.id=m.user_id AND u.active
                WHERE m.role IN ('OWNER','ADMIN') AND m.user_id<>?
                ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
                """,rs->{leaseNotification(rs.getObject("user_id",UUID.class),application,lease,event,type,message,
                        "/api/v1/organizations/"+rs.getObject("organization_id",UUID.class)+"/properties/"+property+"/leases/"+lease);},property,actor);
    }
    public void billingTenant(UUID user,UUID application,UUID lease,UUID event,String type,String message) {
        billingNotification(user,application,lease,event,type,message,"/api/v1/me/leases/"+lease+"/billing");
    }
    private void billingNotification(UUID user,UUID application,UUID lease,UUID event,String type,String message,String url) {
        jdbc.update("INSERT INTO in_app_notifications(id,user_id,application_id,lease_id,billing_event_id,type,message,lease_url) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),user,application,lease,event,type,message,url);
    }
    public void billingManagers(UUID actor,UUID application,UUID lease,UUID property,UUID event,String type,String message) {
        jdbc.query("""
                SELECT DISTINCT ON (m.user_id) m.user_id,m.organization_id FROM organization_memberships m
                JOIN properties p ON p.id=? AND (m.organization_id=p.owner_organization_id OR m.organization_id=p.manager_organization_id)
                JOIN app_users u ON u.id=m.user_id AND u.active
                WHERE m.role IN ('OWNER','ADMIN') AND m.user_id<>?
                ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
                """,rs->{billingNotification(rs.getObject("user_id",UUID.class),application,lease,event,type,message,
                        "/api/v1/organizations/"+rs.getObject("organization_id",UUID.class)+"/properties/"+property+"/leases/"+lease+"/billing");},property,actor);
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
