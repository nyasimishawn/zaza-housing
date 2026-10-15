package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentClaimResponse(
    UUID id,
    BigDecimal amount,
    PaymentChannel channel,
    String reference,
    LocalDate receivedOn,
    String reason,
    String status,
    UUID paymentId,
    String decisionReason,
    Instant createdAt) {}
