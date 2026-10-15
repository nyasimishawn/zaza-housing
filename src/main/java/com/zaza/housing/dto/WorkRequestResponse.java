package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkRequestResponse(
    UUID id,
    WorkKind kind,
    UUID leaseId,
    UUID propertyId,
    UUID unitId,
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
    String completionSummary,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt)
    implements WorkView {}
