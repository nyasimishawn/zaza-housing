package com.zaza.housing.dto;

import com.zaza.housing.enums.InspectionCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record InspectionItem(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,49}") String key,
    @NotBlank @Size(max = 100) String area,
    @NotBlank @Size(max = 150) String name,
    @NotNull InspectionCondition condition,
    @Min(1) @Max(10000) int quantity,
    @NotBlank @Size(max = 1000) String notes,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal estimatedCharge) {}
