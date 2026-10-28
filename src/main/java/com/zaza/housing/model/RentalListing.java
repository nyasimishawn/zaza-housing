package com.zaza.housing.model;

import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.enums.ListingStatus;
import java.time.Instant;
import java.util.UUID;

public record RentalListing(
    UUID id,
    UUID propertyId,
    UUID unitId,
    String title,
    String description,
    boolean showExactLocation,
    ListingStatus status,
    long version,
    UUID publisherOrganizationId,
    Long sourcePropertyVersion,
    Long sourceUnitVersion,
    boolean publiclyVisible,
    PublicListingResponse publicSnapshot,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt,
    java.util.List<String> amenities) {}
