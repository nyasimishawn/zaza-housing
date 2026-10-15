package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkParty;
import java.time.Instant;
import java.util.UUID;

public record WorkDisputeResponse(
    UUID id,
    String reason,
    WorkParty raisedBy,
    String resolution,
    String resolutionMessage,
    Instant createdAt) {}
