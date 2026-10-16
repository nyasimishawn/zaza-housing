package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record InspectionEvidenceResponse(
    UUID id,
    String party,
    String caption,
    String contentType,
    int width,
    int height,
    long byteSize,
    Instant createdAt) {}
