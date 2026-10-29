package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryQuery(
    String kind,
    @Size(max = 200) String q,
    @Size(max = 100) String city,
    @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
    String type,
    @Pattern(regexp = "[A-Za-z]{3}") String currency,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minRent,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal maxRent,
    @Min(0) @Max(100) Integer minBedrooms,
    @Min(0) @Max(100) Integer maxBedrooms,
    String category,
    Boolean verifiedOnly,
    Double latitude,
    Double longitude,
    Double radiusKm,
    Double north,
    Double south,
    Double east,
    Double west,
    String sort,
    @Min(1) @Max(100) Integer limit,
    @Min(0) @Max(10000) Integer offset) {}
