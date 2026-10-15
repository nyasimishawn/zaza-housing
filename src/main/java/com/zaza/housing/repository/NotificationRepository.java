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
  private static final RowMapper<NotificationResponse> NOTIFICATION =
      (rs, row) ->
          new NotificationResponse(
              rs.getObject("id", UUID.class),
              rs.getObject("application_id", UUID.class),
              rs.getString("type"),
              rs.getString("message"),
              rs.getObject("application_id", UUID.class) == null
                  ? null
                  : "/api/v1/me/applications/" + rs.getObject("application_id", UUID.class),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
              rs.getObject("lease_id", UUID.class),
              rs.getString("lease_url"),
              rs.getObject("work_request_id", UUID.class),
              rs.getString("work_request_url"),
              rs.getObject("service_payment_account_id", UUID.class),
              rs.getString("service_payment_url"),
              rs.getObject("conversation_id", UUID.class),
              rs.getString("conversation_url"));
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void append(UUID user, UUID application, UUID event, ApplicationEventType type, String message) {
    jdbc.update(
        "INSERT INTO in_app_notifications(id, user_id, application_id, event_id, type, message)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        user,
        application,
        event,
        type.name(),
        message);
    }
    public void leaseTenant(UUID user,UUID application,UUID lease,UUID event,String type,String message) {
        leaseNotification(user,application,lease,event,type,message,"/api/v1/me/leases/"+lease);
    }
    private void leaseNotification(UUID user,UUID application,UUID lease,UUID event,String type,String message,String url) {
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,application_id,lease_id,lease_event_id,type,message,lease_url)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        user,
        application,
        lease,
        event,
        type,
        message,
        url);
    }
    public void leaseManagers(UUID actor,UUID application,UUID lease,UUID property,UUID event,String type,String message) {
    jdbc.query(
        """
        SELECT DISTINCT ON (m.user_id) m.user_id,m.organization_id FROM organization_memberships m
        JOIN properties p ON p.id=? AND (m.organization_id=p.owner_organization_id OR m.organization_id=p.manager_organization_id)
        JOIN app_users u ON u.id=m.user_id AND u.active
        WHERE m.role IN ('OWNER','ADMIN') AND m.user_id<>?
        ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
        """,
        rs -> {
          leaseNotification(
              rs.getObject("user_id", UUID.class),
              application,
              lease,
              event,
              type,
              message,
              "/api/v1/organizations/"
                  + rs.getObject("organization_id", UUID.class)
                  + "/properties/"
                  + property
                  + "/leases/"
                  + lease);
        },
        property,
        actor);
    }
    public void billingTenant(UUID user,UUID application,UUID lease,UUID event,String type,String message) {
    billingNotification(
        user, application, lease, event, type, message, "/api/v1/me/leases/" + lease + "/billing");
    }
    private void billingNotification(UUID user,UUID application,UUID lease,UUID event,String type,String message,String url) {
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,application_id,lease_id,billing_event_id,type,message,lease_url)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        user,
        application,
        lease,
        event,
        type,
        message,
        url);
    }
    public void billingManagers(UUID actor,UUID application,UUID lease,UUID property,UUID event,String type,String message) {
    jdbc.query(
        """
        SELECT DISTINCT ON (m.user_id) m.user_id,m.organization_id FROM organization_memberships m
        JOIN properties p ON p.id=? AND (m.organization_id=p.owner_organization_id OR m.organization_id=p.manager_organization_id)
        JOIN app_users u ON u.id=m.user_id AND u.active
        WHERE m.role IN ('OWNER','ADMIN') AND m.user_id<>?
        ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
        """,
        rs -> {
          billingNotification(
              rs.getObject("user_id", UUID.class),
              application,
              lease,
              event,
              type,
              message,
              "/api/v1/organizations/"
                  + rs.getObject("organization_id", UUID.class)
                  + "/properties/"
                  + property
                  + "/leases/"
                  + lease
                  + "/billing");
        },
        property,
        actor);
    }

    public void workRecipient(UUID user,UUID actor,UUID work,UUID event,String type,String message,String url) {
        if(user.equals(actor)) return;
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,work_request_id,work_event_id,type,message,work_request_url)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,work_event_id) DO NOTHING",
        UUID.randomUUID(),
        user,
        work,
        event,
        type,
        message,
        url);
    }
    public void workProvider(UUID organization,UUID actor,UUID work,UUID event,String type,String message) {
    jdbc.query(
        "SELECT m.user_id FROM organization_memberships m JOIN app_users u ON u.id=m.user_id AND"
            + " u.active WHERE m.organization_id=? AND m.role IN ('OWNER','ADMIN')",
        r -> {
          workRecipient(
              r.getObject("user_id", UUID.class),
              actor,
              work,
              event,
              type,
              message,
              "/api/v1/organizations/" + organization + "/service-jobs/" + work);
        },
        organization);
    }
    public void workManagers(UUID property,UUID actor,UUID work,UUID event,String type,String message) {
    jdbc.query(
        """
        SELECT DISTINCT ON (m.user_id) m.user_id,m.organization_id FROM organization_memberships m
        JOIN properties p ON p.id=? AND (m.organization_id=p.owner_organization_id OR m.organization_id=p.manager_organization_id)
        JOIN app_users u ON u.id=m.user_id AND u.active WHERE m.role IN ('OWNER','ADMIN')
        ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END
        """,
        r -> {
          workRecipient(
              r.getObject("user_id", UUID.class),
              actor,
              work,
              event,
              type,
              message,
              "/api/v1/organizations/"
                  + r.getObject("organization_id", UUID.class)
                  + "/properties/"
                  + property
                  + "/work-requests/"
                  + work);
        },
        property);
    }

  public void serviceFinance(
      UUID user, UUID actor, UUID account, UUID event, String type, String message) {
    if (user.equals(actor)) return;
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,service_payment_account_id,service_finance_event_id,type,message,service_payment_url)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,service_finance_event_id) DO NOTHING",
        UUID.randomUUID(),
        user,
        account,
        event,
        type,
        message,
        "/api/v1/service-payments/" + account);
  }

  public void serviceFinanceOrganization(
      UUID org, UUID actor, UUID account, UUID event, String type, String message) {
    jdbc.query(
        "SELECT m.user_id FROM organization_memberships m JOIN app_users u ON u.id=m.user_id AND"
            + " u.active WHERE m.organization_id=? AND m.role IN ('OWNER','ADMIN')",
        r -> {
          serviceFinance(r.getObject("user_id", UUID.class), actor, account, event, type, message);
        },
        org);
  }

  public void conversation(
      UUID user, UUID actor, UUID conversation, UUID event, String type, String url) {
    if (user.equals(actor)) return;
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,conversation_id,conversation_event_id,type,message,conversation_url)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,conversation_event_id) DO NOTHING",
        UUID.randomUUID(),
        user,
        conversation,
        event,
        type,
        "A private conversation or viewing was updated. Open it for details.",
        url);
  }

    public List<NotificationResponse> list(UUID user, boolean unreadOnly, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM in_app_notifications WHERE user_id = ?"
            + (unreadOnly ? " AND read_at IS NULL" : "")
            + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
        NOTIFICATION,
        user,
        limit,
        offset);
    }
    public long unread(UUID user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM in_app_notifications WHERE user_id = ? AND read_at IS NULL",
        Long.class,
        user);
  }
    public Optional<NotificationResponse> markRead(UUID user, UUID id) {
    return jdbc
        .query(
            "UPDATE in_app_notifications SET read_at = COALESCE(read_at, clock_timestamp()) WHERE"
                + " user_id = ? AND id = ? RETURNING *",
            NOTIFICATION,
            user,
            id)
        .stream()
        .findFirst();
    }
}
