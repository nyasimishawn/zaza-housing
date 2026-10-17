package com.zaza.housing.dto;

import com.zaza.housing.enums.VerificationKind;
import com.zaza.housing.enums.VerificationStatus;
import java.time.Instant;
import java.util.UUID;

public record VerificationResponse(
    UUID id,
    VerificationKind kind,
    VerificationStatus status,
    long version,
    VerificationSubmissionResponse submission,
    Instant validUntil,
    String resolution,
    boolean assigned,
    Instant createdAt,
    Instant updatedAt) {}
