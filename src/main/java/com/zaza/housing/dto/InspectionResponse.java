package com.zaza.housing.dto;

import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.InspectionStatus;
import java.time.Instant;
import java.util.UUID;

public record InspectionResponse(
    UUID id,
    UUID leaseId,
    InspectionKind kind,
    InspectionStatus status,
    long version,
    InspectionRevisionResponse revision,
    UUID acceptedRevisionId,
    Instant createdAt,
    Instant updatedAt) {}
