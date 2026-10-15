package com.zaza.housing.model;

import com.zaza.housing.enums.ConversationRole;
import java.time.Instant;
import java.util.UUID;

public record ViewingProposal(
    UUID id,
    UUID viewingId,
    UUID proposedBy,
    ConversationRole proposedRole,
    Instant startAt,
    Instant endAt,
    long listingVersion,
    String meetingInstructions,
    String reason,
    Instant createdAt) {}
