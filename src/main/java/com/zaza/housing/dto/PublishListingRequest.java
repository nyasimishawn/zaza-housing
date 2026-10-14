package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PublishListingRequest(@NotNull @PositiveOrZero Long version,
            @NotNull @PositiveOrZero Long propertyVersion, @NotNull @PositiveOrZero Long unitVersion) { }
