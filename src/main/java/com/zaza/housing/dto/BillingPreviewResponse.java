package com.zaza.housing.dto;

import com.zaza.housing.enums.BillingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BillingPreviewResponse(
    UUID leaseId,
    UUID revisionId,
    String currency,
    BillingPolicy policy,
    LocalDate endExclusive,
    BigDecimal deposit,
    BigDecimal rentTotal,
    java.util.List<RentPeriodResponse> rent) {}
