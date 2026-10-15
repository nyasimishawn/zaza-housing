package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReleaseLeaseUnitRequest(@NotNull @PositiveOrZero Long version, @NotNull @AssertTrue Boolean vacancyConfirmed, @NotBlank @Size(max = 2000) String message) { }
