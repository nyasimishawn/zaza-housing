package com.zaza.housing.service;

import com.zaza.housing.repository.AuditRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository audit;

    public AuditService(AuditRepository audit) { this.audit = audit; }

    public void record(UUID actor, UUID organization, String action, UUID entity) {
        audit.record(actor, organization, action, entity);
    }

    public void recordChange(UUID actor, UUID organization, String action, UUID entity,
            String previousValue, String newValue) {
        audit.recordChange(actor, organization, action, entity, previousValue, newValue);
    }

    public void recordSnapshot(UUID actor, UUID organization, String action, UUID entity,
            Object previousValue, Object newValue) {
        audit.recordSnapshot(actor, organization, action, entity, previousValue, newValue);
    }
}
