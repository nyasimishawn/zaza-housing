package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import java.time.Instant;
import java.util.UUID;

public record ProviderJobResponse(
    UUID id,
    WorkKind kind,
    ServiceCategory category,
    String title,
    String description,
    WorkPriority priority,
    WorkStatus status,
    long version,
    String countryCode,
    String city,
    String address,
    String unitLabel,
    UUID assignmentId,
    UUID quoteId,
    UUID visitId,
    UUID disputeId,
    String completionSummary,
    Instant createdAt)
    implements WorkView {}
