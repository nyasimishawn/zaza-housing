package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RefreshApplicationListingRequest(@NotNull @PositiveOrZero Long version, @NotNull @PositiveOrZero Long listingVersion) { }
