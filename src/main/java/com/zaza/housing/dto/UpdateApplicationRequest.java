package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateApplicationRequest(@NotNull @PositiveOrZero Long version, @NotNull @Valid ApplicationDetails details) { }
