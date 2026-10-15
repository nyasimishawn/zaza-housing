package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceAccountResponse(
    UUID id,
    UUID workId,
    UUID assignmentId,
    UUID quoteId,
    UUID providerId,
    String payerType,
    String currency,
    BigDecimal amount,
    int commissionBps,
    BigDecimal fee,
    BigDecimal providerEntitlement,
    String status,
    long version,
    BigDecimal received,
    BigDecimal refunded,
    BigDecimal netCollected,
    BigDecimal due,
    BigDecimal reserved,
    BigDecimal paid,
    BigDecimal feesEarned,
    BigDecimal refundAvailable,
    BigDecimal payoutAvailable,
    Instant eligibleAfter,
    String payoutBlocker,
    String collectionMode,
    Instant createdAt) {}
