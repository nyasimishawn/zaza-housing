package com.zaza.housing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record InspectionMeter(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,49}") String key,
    @NotBlank @Size(max = 100) String label,
    @NotBlank @Size(max = 100) String serialNumber,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 3) BigDecimal reading,
    @NotBlank @Size(max = 30) String unit) {}
