package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record ViewingResponse(
    UUID id,
    UUID conversationId,
    String status,
    long version,
    UUID currentProposalId,
    UUID confirmedProposalId,
    ViewingProposalResponse proposal,
    ViewingProposalResponse confirmedSlot,
    Instant createdAt) {}
