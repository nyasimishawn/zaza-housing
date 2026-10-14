package com.zaza.housing.dto;

import com.zaza.housing.enums.PropertyStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangePropertyStatusRequest(@NotNull @PositiveOrZero Long version, @NotNull PropertyStatus status) { }
