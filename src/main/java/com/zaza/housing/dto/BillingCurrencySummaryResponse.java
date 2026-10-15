package com.zaza.housing.dto;

import java.math.BigDecimal;

public record BillingCurrencySummaryResponse(
    String currency,
    long leases,
    BigDecimal outstanding,
    BigDecimal dueNow,
    BigDecimal overdue,
    BigDecimal rentCollected,
    BigDecimal depositHeld,
    BigDecimal received,
    BigDecimal refunded,
    BigDecimal unallocated) {}
