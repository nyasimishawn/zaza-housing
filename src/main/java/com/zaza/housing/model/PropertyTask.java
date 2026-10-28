package com.zaza.housing.model;

import com.zaza.housing.enums.PropertyTaskKind;
import com.zaza.housing.enums.WorkPriority;
import java.time.Instant;
import java.util.UUID;

public record PropertyTask(
    UUID id,
    UUID ownerOrganizationId,
    UUID propertyId,
    UUID unitId,
    String propertyName,
    String locationLabel,
    UUID assigneeOrganizationId,
    UUID assigneeId,
    String assigneeName,
    PropertyTaskKind kind,
    WorkPriority priority,
    String title,
    String description,
    Instant dueAt,
    String status,
    long version,
    String completionSummary,
    Instant submittedAt,
    Instant completedAt,
    UUID completedBy,
    UUID createdBy,
    UUID createdByOrganizationId,
    Instant createdAt,
    Instant updatedAt) {}
