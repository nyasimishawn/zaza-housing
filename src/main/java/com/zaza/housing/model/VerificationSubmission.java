package com.zaza.housing.model;

import com.zaza.housing.dto.VerificationDetails;
import java.time.Instant;
import java.util.UUID;

public record VerificationSubmission(
    UUID id,
    UUID caseId,
    int number,
    VerificationDetails details,
    UUID preparedBy,
    boolean submitted,
    Instant createdAt) {}
