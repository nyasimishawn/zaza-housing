package com.zaza.housing.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(UUID actor, UUID organization, String action, UUID entity) {
        jdbc.update("INSERT INTO audit_events(id, actor_id, organization_id, action, entity_id) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), actor, organization, action, entity);
    }

    public void recordChange(UUID actor, UUID organization, String action, UUID entity,
            String previousValue, String newValue) {
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, organization_id, action, entity_id, changes)
                VALUES (?, ?, ?, ?, ?, jsonb_build_object('previousValue', CAST(? AS text), 'newValue', CAST(? AS text)))
                """, UUID.randomUUID(), actor, organization, action, entity, previousValue, newValue);
    }
}
