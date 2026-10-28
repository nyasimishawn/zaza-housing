package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record CaretakerEvent(
    UUID id,
    String targetKind,
    UUID targetId,
    UUID actorId,
    UUID organizationId,
    String action,
    String message,
    Instant createdAt) {}
