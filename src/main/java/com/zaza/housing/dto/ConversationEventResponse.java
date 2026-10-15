package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record ConversationEventResponse(
    UUID id, String type, UUID entityId, String message, Instant createdAt) {}
