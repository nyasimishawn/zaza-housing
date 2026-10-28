package com.zaza.housing.dto;

public record SupportEvidenceResponse(
    java.util.UUID id,
    java.util.UUID caseId,
    java.util.UUID authorId,
    String caption,
    boolean internal,
    String contentType,
    int width,
    int height,
    long byteSize,
    String sha256,
    java.time.Instant createdAt,
    String contentUrl) {}
