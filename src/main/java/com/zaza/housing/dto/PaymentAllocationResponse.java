package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAllocationResponse(
    UUID id,
    UUID paymentId,
    UUID invoiceId,
    BigDecimal amount,
    boolean reversed,
    Instant createdAt) {}
