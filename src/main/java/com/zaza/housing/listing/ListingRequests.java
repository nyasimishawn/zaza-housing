package com.zaza.housing.listing;

import com.zaza.housing.property.PropertyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class ListingRequests {
    private ListingRequests() { }
    public record Details(@NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 5000) String description, @NotNull Boolean showExactLocation) { }
    public record Create(@NotNull UUID unitId, @NotNull @Valid Details details) { }
    public record Update(@NotNull @PositiveOrZero Long version, @NotNull @Valid Details details) { }
    public record Publish(@NotNull @PositiveOrZero Long version,
            @NotNull @PositiveOrZero Long propertyVersion, @NotNull @PositiveOrZero Long unitVersion) { }
    public record Withdraw(@NotNull @PositiveOrZero Long version) { }
    public record Search(@Size(min = 2, max = 200) String q,
            @Size(min = 1, max = 100) String city, @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            PropertyType type, @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minRent,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal maxRent,
            @Min(0) @Max(100) Integer minBedrooms, @Min(0) @Max(100) Integer maxBedrooms,
            ListingSort sort, @Min(1) @Max(100) Integer limit, @Min(0) @Max(10000) Integer offset) { }
}
