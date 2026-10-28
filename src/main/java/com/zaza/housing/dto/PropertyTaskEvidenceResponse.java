package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record PropertyTaskEvidenceResponse(
    UUID id,
    UUID taskId,
    UUID authorId,
    String caption,
    String contentType,
    int width,
    int height,
    long byteSize,
    String sha256,
    Instant createdAt) {}
