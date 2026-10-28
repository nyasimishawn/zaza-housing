package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CaretakerGrantCommand(
    @NotNull UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 2000) String message,
    @NotNull @AssertTrue Boolean consent,
    @NotBlank String password) {}
