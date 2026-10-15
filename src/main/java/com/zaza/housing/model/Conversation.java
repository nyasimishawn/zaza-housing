package com.zaza.housing.model;

import com.zaza.housing.enums.ConversationChannel;
import com.zaza.housing.enums.ConversationKind;
import java.time.Instant;
import java.util.UUID;

public record Conversation(
    UUID id,
    ConversationKind kind,
    ConversationChannel channel,
    UUID listingId,
    UUID leaseId,
    UUID workId,
    UUID propertyId,
    UUID unitId,
    UUID customerId,
    UUID providerId,
    UUID assignmentId,
    String subject,
    String status,
    long version,
    long lastSequence,
    Instant createdAt,
    Instant updatedAt) {}
