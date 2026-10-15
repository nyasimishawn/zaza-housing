package com.zaza.housing.model;

import com.zaza.housing.enums.LeaseStatus;
import java.time.Instant;
import java.util.UUID;

public record RentalLease(UUID id, UUID applicationId, UUID propertyId, UUID unitId, UUID tenantId, UUID ownerOrganizationId,
        LeaseStatus status, long version, UUID currentRevisionId, UUID sharedRevisionId, long unitVersion,
        Instant firstSharedAt, Instant activatedAt, Instant closedAt, Instant unitReleasedAt, Instant createdAt, Instant updatedAt) { }
