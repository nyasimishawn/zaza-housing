package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record AgencyCommissionRequest(
    @NotNull UUID requestId,
    @NotNull UUID leadId,
    @NotNull UUID agentId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2)
        BigDecimal amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank @Size(min = 20, max = 2000) String agreement,
    @NotNull @AssertTrue Boolean consent) {}
