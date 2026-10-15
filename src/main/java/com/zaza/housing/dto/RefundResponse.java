package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    UUID paymentId,
    boolean deposit,
    BigDecimal amount,
    PaymentChannel channel,
    String reference,
    LocalDate paidOn,
    String reason,
    Instant createdAt) {}
