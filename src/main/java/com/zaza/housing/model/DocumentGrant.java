package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record DocumentGrant(
    UUID id,
    UUID documentId,
    UUID documentVersionId,
    UUID applicationId,
    long version,
    Instant expiresAt,
    Instant revokedAt,
    Instant createdAt) {}
