package com.zaza.housing.dto;

import com.zaza.housing.enums.ImageStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeImageStatusRequest(@NotNull @PositiveOrZero Long version, @NotNull ImageStatus status) { }
