package com.zaza.housing.dto;

import com.zaza.housing.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReviewApplicationResponse(UUID id, UUID applicantId, UUID listingId, ApplicationStatus status, long version,
        long listingVersion, long profileVersion, ApplicationDetails details, ApplicantProfileSnapshot applicant,
        PublicListingResponse listing, Instant submittedAt, Instant createdAt, Instant updatedAt) { }
