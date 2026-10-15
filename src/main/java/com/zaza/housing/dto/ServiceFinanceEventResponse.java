package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceFinanceEventResponse(
    UUID id, String type, UUID entityId, BigDecimal amount, String message, Instant createdAt) {}
