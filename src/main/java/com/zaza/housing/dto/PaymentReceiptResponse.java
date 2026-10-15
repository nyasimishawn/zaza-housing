package com.zaza.housing.dto;

import java.util.UUID;

public record PaymentReceiptResponse(
    UUID receiptId,
    UUID leaseId,
    String currency,
    String method,
    PaymentResponse payment,
    java.util.List<PaymentAllocationResponse> allocations,
    java.util.List<RefundResponse> refunds) {}
