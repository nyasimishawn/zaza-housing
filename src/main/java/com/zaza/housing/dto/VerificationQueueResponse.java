package com.zaza.housing.dto;

import com.zaza.housing.enums.VerificationKind;
import com.zaza.housing.enums.VerificationStatus;
import java.time.Instant;
import java.util.UUID;

public record VerificationQueueResponse(
    UUID id,
    VerificationKind kind,
    VerificationStatus status,
    long version,
    boolean assignedToMe,
    Instant submittedAt,
    Instant updatedAt) {}
