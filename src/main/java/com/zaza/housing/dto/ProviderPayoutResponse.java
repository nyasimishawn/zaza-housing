package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProviderPayoutResponse(
    UUID id,
    UUID accountId,
    UUID destinationId,
    BigDecimal amount,
    String currency,
    String status,
    String decisionReason,
    UUID requestedBy,
    UUID approvedBy,
    String transferReference,
    LocalDate paidOn,
    Instant createdAt) {}
