package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record TeamInvitation(
    UUID id,
    UUID organizationId,
    String organizationName,
    String email,
    String role,
    UUID invitedBy,
    UUID requestId,
    String tokenHash,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt,
    Instant lastSentAt,
    int sendCount,
    String deliveryStatus) {}
