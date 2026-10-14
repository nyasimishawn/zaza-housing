package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateVideoRequest(@NotNull @PositiveOrZero Long version, @NotNull @Size(max = 200) String caption) { }
