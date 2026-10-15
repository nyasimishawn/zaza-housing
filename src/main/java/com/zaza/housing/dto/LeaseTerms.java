package com.zaza.housing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record LeaseTerms(
        @NotBlank @Size(max = 150) String title,
        @NotNull LocalDate startDate, @NotNull LocalDate endDate,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyRent,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal deposit,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotNull @Min(1) @Max(28) Integer rentDueDay,
        @NotBlank @Size(min = 20, max = 20000) String agreementText) { }
