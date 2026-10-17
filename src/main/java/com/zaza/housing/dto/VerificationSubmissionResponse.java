package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record VerificationSubmissionResponse(
    UUID id, int number, VerificationDetails details, boolean submitted, Instant createdAt) {}
