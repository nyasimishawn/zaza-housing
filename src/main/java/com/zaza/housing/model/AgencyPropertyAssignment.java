package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record AgencyPropertyAssignment(
    UUID id,
    UUID agencyId,
    UUID propertyId,
    UUID agentId,
    boolean active,
    long version,
    Instant updatedAt) {}
