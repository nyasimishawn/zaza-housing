package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record ServiceReviewResponse(
    UUID id, UUID providerId, int rating, String comment, Instant createdAt) {}
