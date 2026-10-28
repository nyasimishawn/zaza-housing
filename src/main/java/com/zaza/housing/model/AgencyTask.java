package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record AgencyTask(
    UUID id,
    UUID agencyId,
    UUID leadId,
    UUID assigneeId,
    String title,
    String kind,
    Instant dueAt,
    String description,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
