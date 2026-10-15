package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationChannel;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.enums.ConversationRole;
import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
    UUID id,
    ConversationKind kind,
    ConversationChannel channel,
    UUID referenceId,
    String subject,
    String status,
    long version,
    long lastSequence,
    ConversationRole role,
    long unreadMessages,
    long readThrough,
    boolean muted,
    Instant createdAt,
    Instant updatedAt) {}
