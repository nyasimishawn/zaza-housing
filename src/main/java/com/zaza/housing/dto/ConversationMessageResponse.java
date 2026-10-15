package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationRole;
import java.time.Instant;
import java.util.UUID;

public record ConversationMessageResponse(
    UUID id,
    long sequence,
    ConversationRole senderRole,
    boolean mine,
    String body,
    boolean redacted,
    Instant createdAt) {}
