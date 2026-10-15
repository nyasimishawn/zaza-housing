package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record ViewingAppointment(
    UUID id,
    UUID conversationId,
    UUID currentProposalId,
    UUID confirmedProposalId,
    String status,
    long version,
    Instant createdAt) {}
