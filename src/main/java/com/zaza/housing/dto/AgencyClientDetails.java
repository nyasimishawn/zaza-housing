package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AgencyClientDetails(
    @NotBlank @Size(max = 150) String fullName,
    @Email @Size(max = 254) String email,
    @Size(max = 40) String phone,
    @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
    @NotBlank @Size(max = 100) String city,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minBudget,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal maxBudget,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
    @Min(0) @Max(30) Integer minBedrooms,
    @NotBlank @Size(min = 20, max = 2000) String requirements,
    @NotNull @AssertTrue Boolean consentToStore) {}
