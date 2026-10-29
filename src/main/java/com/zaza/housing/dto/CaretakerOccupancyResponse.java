package com.zaza.housing.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CaretakerOccupancyResponse(
    UUID id,
    UUID propertyId,
    UUID unitId,
    String unitLabel,
    String tenantName,
    String status,
    LocalDate startDate,
    LocalDate effectiveEndDate,
    Instant activatedAt,
    Instant unitReleasedAt,
    boolean inspectionTrackingEnabled,
    boolean moveInRequired) {}
