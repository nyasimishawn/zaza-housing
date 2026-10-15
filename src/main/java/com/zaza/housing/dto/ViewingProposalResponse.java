package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationRole;
import java.time.Instant;
import java.util.UUID;

public record ViewingProposalResponse(
    UUID id,
    ConversationRole proposedRole,
    Instant startAt,
    Instant endAt,
    long listingVersion,
    String meetingInstructions,
    String reason,
    Instant createdAt) {}
