package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record VideoUploadJob(UUID id, UUID propertyId, UUID organizationId, UUID actorId, UUID requestId,
    long propertyVersion, String caption, String sourceKey, String sourceHash, long sourceSize,
    boolean sourceDeleted, String status, long version, int attempts, int progress, String failureCode,
    UUID videoId, UUID leaseId, Instant createdAt, Instant updatedAt) {}
