package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record InspectionAttestationResponse(
    UUID revisionId,
    String party,
    String decision,
    String method,
    String documentHash,
    boolean vacancyConfirmed,
    Instant recordedAt) {}
