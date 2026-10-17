package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentEventResponse(
    UUID id, String action, String party, String reason, Instant createdAt) {}
