package com.zaza.housing.model;

import com.zaza.housing.enums.ConversationReportCategory;
import java.time.Instant;
import java.util.UUID;

public record ConversationReport(
    UUID id,
    UUID conversationId,
    UUID messageId,
    UUID reporterId,
    ConversationReportCategory category,
    String reason,
    String status,
    UUID assignedTo,
    String resolution,
    String resolutionReason,
    long version,
    Instant createdAt) {}
