package com.zaza.housing.dto;

import com.zaza.housing.enums.LeaseStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LeaseResponse(UUID id, UUID applicationId, LeaseStatus status, long version, LeaseRevisionResponse revision,
        List<LeaseSignatureResponse> signatures, LeaseTerminationResponse termination, LocalDate effectiveEndDate,
        Instant activatedAt, Instant closedAt, Instant unitReleasedAt, Instant createdAt, Instant updatedAt) { }
