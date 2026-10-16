package com.zaza.housing.model;

import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.InspectionStatus;
import java.time.Instant;
import java.util.UUID;

public record LeaseInspection(
    UUID id,
    UUID leaseId,
    InspectionKind kind,
    InspectionStatus status,
    long version,
    UUID currentRevisionId,
    UUID sharedRevisionId,
    UUID acceptedRevisionId,
    Instant createdAt,
    Instant updatedAt) {}
