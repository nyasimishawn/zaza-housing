package com.zaza.housing.dto;

import com.zaza.housing.enums.UnitStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeUnitStatusRequest(@NotNull @PositiveOrZero Long version, @NotNull UnitStatus status) { }
