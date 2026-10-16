package com.zaza.housing.model;

import com.zaza.housing.enums.VerificationKind;
import com.zaza.housing.enums.VerificationStatus;
import java.time.Instant;
import java.util.UUID;

public record VerificationCase(
    UUID id,
    UUID ownerUserId,
    UUID organizationId,
    VerificationKind kind,
    VerificationStatus status,
    long version,
    UUID currentSubmissionId,
    UUID sharedSubmissionId,
    UUID assignedTo,
    Instant validUntil,
    String resolution,
    UUID createdBy,
    Instant submittedAt,
    Instant createdAt,
    Instant updatedAt) {}
