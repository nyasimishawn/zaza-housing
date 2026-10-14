package com.zaza.housing.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

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

    public void recordSnapshot(UUID actor, UUID organization, String action, UUID entity,
            Object previousValue, Object newValue) {
        jdbc.update("""
                INSERT INTO audit_events(id, actor_id, organization_id, action, entity_id, changes)
                VALUES (?, ?, ?, ?, ?, jsonb_build_object('previousValue', CAST(? AS jsonb), 'newValue', CAST(? AS jsonb)))
                """, UUID.randomUUID(), actor, organization, action, entity,
                previousValue == null ? null : json.writeValueAsString(previousValue),
                newValue == null ? null : json.writeValueAsString(newValue));
    }
}
