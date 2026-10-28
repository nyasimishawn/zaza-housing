package com.zaza.housing.model;

import com.zaza.housing.enums.CaretakerCapability;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CaretakerGrant(
    UUID id,
    UUID organizationId,
    UUID propertyId,
    UUID userId,
    String userName,
    Map<String, Object> propertySnapshot,
    Set<CaretakerCapability> capabilities,
    String responsibilities,
    long propertyVersion,
    String status,
    boolean effective,
    long version,
    Instant expiresAt,
    UUID invitedBy,
    Instant acceptedAt,
    Instant closedAt,
    Instant createdAt,
    Instant updatedAt) {}
