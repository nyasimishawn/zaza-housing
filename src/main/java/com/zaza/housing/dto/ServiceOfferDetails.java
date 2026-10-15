package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.ServicePriceUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ServiceOfferDetails(
    @NotNull ServiceCategory category,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 20, max = 4000) String description,
    @NotNull ServicePriceUnit priceUnit,
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2)
        BigDecimal amount,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {}
