package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ActivateLeaseRequest(@NotNull @PositiveOrZero Long version, @NotNull @AssertTrue Boolean possessionConfirmed) { }
