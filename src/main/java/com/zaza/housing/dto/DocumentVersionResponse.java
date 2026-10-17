package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionResponse(
    UUID id,
    int number,
    String contentType,
    String contentHash,
    int width,
    int height,
    long byteSize,
    Instant createdAt) {}
