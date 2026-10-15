package com.zaza.housing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AllocatePaymentRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull UUID invoiceId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2)
        BigDecimal amount) {}
