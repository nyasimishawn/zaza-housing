package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record BillingEntryResponse(
    UUID id, String type, UUID entityId, UUID actorId, String message, Instant createdAt) {}
