package com.zaza.housing.dto;

import com.zaza.housing.enums.UnitAvailability;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UnitDetails(
            @NotBlank @Size(max = 100) String label,
            @NotNull @Min(0) @Max(100) Integer bedrooms,
            @NotNull @Min(0) @Max(100) Integer bathrooms,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal floorAreaSqm,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyRent,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal deposit,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @NotNull UnitAvailability availability) { }
