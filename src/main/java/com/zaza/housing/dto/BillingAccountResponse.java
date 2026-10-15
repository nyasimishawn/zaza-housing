package com.zaza.housing.dto;

import com.zaza.housing.enums.BillingPolicy;
import java.math.BigDecimal;
import java.util.UUID;

public record BillingAccountResponse(
    UUID leaseId,
    UUID revisionId,
    String currency,
    BillingPolicy policy,
    String status,
    long version,
    BigDecimal invoiced,
    BigDecimal credited,
    BigDecimal allocated,
    BigDecimal outstanding,
    BigDecimal dueNow,
    BigDecimal overdue,
    BigDecimal received,
    BigDecimal refunded,
    BigDecimal unallocated,
    BigDecimal rentCollected,
    BigDecimal depositHeld,
    BigDecimal depositDeducted,
    long pendingPaymentReports,
    boolean checkoutReady) {}
