package com.zaza.housing.dto;

import com.zaza.housing.enums.PaymentChannel;
import java.time.Instant;
import java.util.UUID;

public record PayoutDestinationResponse(
    UUID id,
    UUID providerId,
    PaymentChannel channel,
    String beneficiaryToken,
    String maskedLabel,
    String currency,
    String status,
    Instant createdAt) {}
