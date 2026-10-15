package com.zaza.housing.dto;

import com.zaza.housing.enums.BillingPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OpenBillingRequest(
    @NotNull UUID requestId, @Min(0) long leaseVersion, @NotNull BillingPolicy policy) {}
