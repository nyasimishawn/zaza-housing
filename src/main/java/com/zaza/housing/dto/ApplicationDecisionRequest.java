package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ApplicationDecisionRequest(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 2000) String message) { }
