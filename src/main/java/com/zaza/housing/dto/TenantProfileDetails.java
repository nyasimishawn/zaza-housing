package com.zaza.housing.dto;

import com.zaza.housing.enums.EmploymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TenantProfileDetails(
        @NotBlank @Size(max = 150) String legalName,
        @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{6,14}") String phone,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
        @NotBlank @Size(max = 100) String city,
        @NotNull EmploymentStatus employmentStatus,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyIncome,
        @Pattern(regexp = "[A-Za-z]{3}") String incomeCurrency) { }
