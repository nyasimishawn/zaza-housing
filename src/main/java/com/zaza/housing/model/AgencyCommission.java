package com.zaza.housing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AgencyCommission(
    UUID id,
    UUID agencyId,
    UUID leadId,
    UUID leaseId,
    UUID agentId,
    String leadTitle,
    String agentName,
    BigDecimal amount,
    String currency,
    String agreement,
    String status,
    long version,
    UUID createdBy,
    UUID approvedBy,
    Instant approvedAt,
    UUID paidBy,
    Instant paidAt,
    String externalReference,
    Instant createdAt,
    Instant updatedAt) {}
