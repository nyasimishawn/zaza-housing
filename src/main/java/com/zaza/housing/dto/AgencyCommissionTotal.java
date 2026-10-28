package com.zaza.housing.dto;

import java.math.BigDecimal;

public record AgencyCommissionTotal(
    String currency, BigDecimal earned, BigDecimal approved, BigDecimal paid) {}
