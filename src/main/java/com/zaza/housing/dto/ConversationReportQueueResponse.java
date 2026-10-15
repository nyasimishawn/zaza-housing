package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationReportCategory;
import java.time.Instant;
import java.util.UUID;

public record ConversationReportQueueResponse(
    UUID id,
    ConversationReportCategory category,
    String status,
    long version,
    boolean assignedToMe,
    Instant createdAt) {}
