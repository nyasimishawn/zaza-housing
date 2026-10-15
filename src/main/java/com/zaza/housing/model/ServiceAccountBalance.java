package com.zaza.housing.model;

import java.math.BigDecimal;

public record ServiceAccountBalance(
    BigDecimal received,
    BigDecimal refunded,
    BigDecimal reserved,
    BigDecimal paid,
    BigDecimal feesEarned) {}
