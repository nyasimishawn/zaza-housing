package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record LeaseVersionRequest(@NotNull @PositiveOrZero Long version) { }
