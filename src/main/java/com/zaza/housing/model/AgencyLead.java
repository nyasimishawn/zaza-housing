package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record AgencyLead(
    UUID id,
    UUID agencyId,
    UUID clientId,
    String title,
    String stage,
    UUID assignedTo,
    UUID propertyId,
    UUID listingId,
    UUID enquiryId,
    UUID viewingId,
    UUID applicationId,
    UUID leaseId,
    long version,
    Instant wonAt,
    Instant createdAt,
    Instant updatedAt) {}
