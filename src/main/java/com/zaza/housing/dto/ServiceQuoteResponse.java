package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceQuoteResponse(
    UUID id,
    UUID assignmentId,
    BigDecimal amount,
    String currency,
    String scope,
    Instant validUntil,
    String status,
    Instant createdAt) {}
