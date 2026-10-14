package com.zaza.housing.dto;

import com.zaza.housing.enums.ListingSort;
import com.zaza.housing.enums.PropertyType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ListingSearchRequest(@Size(min = 2, max = 200) String q,
            @Size(min = 1, max = 100) String city, @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            PropertyType type, @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minRent,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal maxRent,
            @Min(0) @Max(100) Integer minBedrooms, @Min(0) @Max(100) Integer maxBedrooms,
            ListingSort sort, @Min(1) @Max(100) Integer limit, @Min(0) @Max(10000) Integer offset) { }
