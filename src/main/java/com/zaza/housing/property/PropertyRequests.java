package com.zaza.housing.property;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class PropertyRequests {
    private PropertyRequests() { }

    public record PropertyDetails(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 5000) String description,
            @NotNull PropertyType type,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 250) String address,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 7) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7) BigDecimal longitude) { }

    public record UpdateProperty(@NotNull @PositiveOrZero Long version,
            @NotNull @Valid PropertyDetails details) { }
    public record ChangePropertyStatus(@NotNull @PositiveOrZero Long version, @NotNull PropertyStatus status) { }
    // Null removes the agency delegation. It never changes property ownership.
    public record ChangeManagement(@NotNull @PositiveOrZero Long version, UUID managerOrganizationId) { }

    public record UnitDetails(
            @NotBlank @Size(max = 100) String label,
            @NotNull @Min(0) @Max(100) Integer bedrooms,
            @NotNull @Min(0) @Max(100) Integer bathrooms,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal floorAreaSqm,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyRent,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal deposit,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @NotNull UnitAvailability availability) { }

    public record UpdateUnit(@NotNull @PositiveOrZero Long version, @NotNull @Valid UnitDetails details) { }
    public record ChangeUnitStatus(@NotNull @PositiveOrZero Long version, @NotNull UnitStatus status) { }
}
