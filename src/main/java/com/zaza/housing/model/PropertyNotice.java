package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record PropertyNotice(
    UUID id,
    UUID propertyId,
    UUID leaseId,
    UUID tenantId,
    UUID ownerOrganizationId,
    String propertyName,
    String unitLabel,
    String tenantName,
    String kind,
    String title,
    String message,
    Instant startsAt,
    Instant endsAt,
    String status,
    String tenantResponse,
    long version,
    UUID createdBy,
    UUID createdByOrganizationId,
    Instant publishedAt,
    UUID publishedBy,
    Instant cancelledAt,
    Instant createdAt,
    Instant updatedAt) {}
