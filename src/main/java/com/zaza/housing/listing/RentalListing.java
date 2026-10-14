package com.zaza.housing.listing;

import java.time.Instant;
import java.util.UUID;

public record RentalListing(UUID id, UUID propertyId, UUID unitId, String title, String description,
        boolean showExactLocation, ListingStatus status, long version, UUID publisherOrganizationId,
        Long sourcePropertyVersion, Long sourceUnitVersion, boolean publiclyVisible,
        PublicListing publicSnapshot, Instant publishedAt, Instant createdAt, Instant updatedAt) { }
