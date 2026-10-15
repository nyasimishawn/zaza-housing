package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DepositSettlementResponse(
    UUID id,
    BigDecimal heldAtProposal,
    BigDecimal deduction,
    BigDecimal refundDue,
    String reason,
    String status,
    String decisionReason,
    Instant createdAt) {}
