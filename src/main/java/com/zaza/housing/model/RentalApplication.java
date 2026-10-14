package com.zaza.housing.model;

import com.zaza.housing.dto.ApplicantProfileSnapshot;
import com.zaza.housing.dto.ApplicationDetails;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;

public record RentalApplication(UUID id, UUID applicantId, UUID propertyId, UUID unitId, UUID listingId,
        ApplicationStatus status, long version, long listingVersion, long propertyVersion, long unitVersion,
        long profileVersion, ApplicationDetails details, ApplicantProfileSnapshot applicant, PublicListingResponse listing,
        Instant submittedAt, Instant createdAt, Instant updatedAt) { }
