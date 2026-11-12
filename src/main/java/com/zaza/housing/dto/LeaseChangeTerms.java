package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record LeaseChangeTerms(
    @NotBlank @Pattern(regexp="RENEWAL|AMENDMENT") String kind,
    @NotNull LocalDate effectiveDate, @NotNull LocalDate endDate,
    @NotNull @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal monthlyRent,
    @NotBlank @Size(min=20,max=20000) String agreementText) { }
