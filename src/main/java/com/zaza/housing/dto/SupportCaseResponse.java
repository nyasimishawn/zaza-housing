package com.zaza.housing.dto;

public record SupportCaseResponse(
    java.util.UUID id,
    java.util.UUID reporterId,
    String kind,
    String category,
    String title,
    String description,
    java.util.UUID listingId,
    String status,
    String priority,
    java.util.UUID assignedTo,
    long version,
    java.time.Instant responseDueAt,
    java.time.Instant resolutionDueAt,
    java.time.Instant firstResponseAt,
    String resolution,
    java.time.Instant resolvedAt,
    java.time.Instant closedAt,
    java.time.Instant createdAt,
    java.time.Instant updatedAt,
    boolean redacted) {}
