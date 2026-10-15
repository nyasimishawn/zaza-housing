package com.zaza.housing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServicePaymentAccount(
    UUID id,
    UUID workId,
    UUID assignmentId,
    UUID quoteId,
    UUID providerId,
    UUID payerUserId,
    UUID payerOrganizationId,
    String currency,
    BigDecimal amount,
    int commissionBps,
    BigDecimal fee,
    String status,
    long version,
    Instant createdAt) {}
