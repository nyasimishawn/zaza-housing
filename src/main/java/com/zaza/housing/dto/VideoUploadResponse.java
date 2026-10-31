package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record VideoUploadResponse(UUID id, UUID propertyId, String caption, String status, long version,
    int attempts, int progress, String failureCode, UUID videoId, boolean retryAllowed,
    Instant createdAt, Instant updatedAt) {}
