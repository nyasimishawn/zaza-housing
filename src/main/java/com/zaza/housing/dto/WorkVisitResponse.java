package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkVisitResponse(
    UUID id,
    UUID assignmentId,
    Instant startAt,
    Instant endAt,
    String accessInstructions,
    String status,
    Instant createdAt) {}
