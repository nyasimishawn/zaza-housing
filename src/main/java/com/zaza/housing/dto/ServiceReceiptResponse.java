package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ServiceReceiptResponse(
    UUID id,
    UUID claimId,
    BigDecimal amount,
    PaymentChannel channel,
    String reference,
    LocalDate receivedOn,
    BigDecimal refunded,
    boolean voided,
    Instant createdAt) {}
