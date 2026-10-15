package com.zaza.housing.model;

import com.zaza.housing.enums.BillingPolicy;
import java.util.UUID;

public record BillingAccount(
    UUID leaseId,
    UUID revisionId,
    String currency,
    BillingPolicy policy,
    String status,
    long version) {}
