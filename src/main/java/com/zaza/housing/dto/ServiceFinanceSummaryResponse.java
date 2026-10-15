package com.zaza.housing.dto;

import java.math.BigDecimal;

public record ServiceFinanceSummaryResponse(
    String currency,
    long accounts,
    BigDecimal quoted,
    BigDecimal received,
    BigDecimal refunded,
    BigDecimal reserved,
    BigDecimal paid,
    BigDecimal feesEarned) {}
