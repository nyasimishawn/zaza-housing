package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record AgencyEvent(
    UUID id,
    String targetKind,
    UUID targetId,
    UUID actorId,
    String action,
    String message,
    Instant createdAt) {}
