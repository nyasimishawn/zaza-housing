package com.zaza.housing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreditInvoiceRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2)
        BigDecimal amount,
    @NotBlank @Size(max = 2000) String reason) {}
