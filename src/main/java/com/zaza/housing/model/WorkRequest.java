package com.zaza.housing.model;

import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkRequest(
    UUID id,
    WorkKind kind,
    UUID leaseId,
    UUID propertyId,
    UUID unitId,
    UUID tenantId,
    UUID reporterId,
    UUID ownerOrganizationId,
    ServiceCategory category,
    String title,
    String description,
    WorkPriority priority,
    WorkStatus status,
    long version,
    UUID providerId,
    UUID assignmentId,
    UUID quoteId,
    UUID visitId,
    UUID disputeId,
    String countryCode,
    String city,
    String address,
    String unitLabel,
    String completionSummary,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt) {}
