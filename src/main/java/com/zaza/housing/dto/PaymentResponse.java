package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UUID claimId,
    PaymentChannel channel,
    String reference,
    LocalDate receivedOn,
    BigDecimal amount,
    BigDecimal allocated,
    BigDecimal refunded,
    BigDecimal available,
    boolean voided,
    UUID verifiedBy,
    Instant createdAt) {}
