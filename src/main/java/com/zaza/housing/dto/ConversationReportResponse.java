package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationReportCategory;
import java.time.Instant;
import java.util.UUID;

public record ConversationReportResponse(
    UUID id,
    UUID conversationId,
    UUID messageId,
    ConversationReportCategory category,
    String reason,
    String status,
    long version,
    String resolution,
    String resolutionReason,
    Instant createdAt) {}
