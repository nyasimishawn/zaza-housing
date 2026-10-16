package com.zaza.housing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InspectionEventResponse(
    UUID id,
    String action,
    String party,
    UUID revisionId,
    String reason,
    List<String> itemKeys,
    Instant createdAt) {}
