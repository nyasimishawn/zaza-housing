package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record InspectionEvidence(
    UUID id,
    UUID inspectionId,
    UUID uploaderId,
    String party,
    String caption,
    String storageKey,
    String contentType,
    int width,
    int height,
    long byteSize,
    Instant createdAt) {}
