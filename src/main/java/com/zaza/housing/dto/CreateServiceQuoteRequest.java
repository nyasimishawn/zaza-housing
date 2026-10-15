package com.zaza.housing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateServiceQuoteRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal amount,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
    @NotNull Instant validUntil,
    @NotBlank @Size(min = 20, max = 4000) String scope) {}
