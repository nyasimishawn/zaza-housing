package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationRole;
import java.time.Instant;
import java.util.UUID;

public record ViewingHistoryResponse(
    UUID id,
    String action,
    ConversationRole role,
    UUID proposalId,
    String reason,
    Instant createdAt) {}
