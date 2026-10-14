package com.zaza.housing.dto;

import com.zaza.housing.enums.VideoStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeVideoStatusRequest(@NotNull @PositiveOrZero Long version, @NotNull VideoStatus status) { }
