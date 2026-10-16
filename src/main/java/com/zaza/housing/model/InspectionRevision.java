package com.zaza.housing.model;

import com.zaza.housing.dto.InspectionDetails;
import java.time.Instant;
import java.util.UUID;

public record InspectionRevision(
    UUID id,
    UUID inspectionId,
    int number,
    InspectionDetails details,
    String currency,
    String document,
    String documentHash,
    Instant createdAt,
    boolean shared) {}
