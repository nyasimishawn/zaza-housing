package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SubmitApplicationRequest(@NotNull @PositiveOrZero Long version, @NotNull @PositiveOrZero Long profileVersion,
        @NotNull @PositiveOrZero Long listingVersion, @NotNull @AssertTrue Boolean consentToShare) { }
