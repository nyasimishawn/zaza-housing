package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record InspectionRevisionResponse(
    UUID id,
    int number,
    InspectionDetails details,
    String currency,
    String documentHash,
    Instant createdAt,
    boolean shared) {}
