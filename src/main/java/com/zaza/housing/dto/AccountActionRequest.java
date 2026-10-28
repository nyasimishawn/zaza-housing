package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record AccountActionRequest(
    @NotNull java.util.UUID requestId,
    @NotNull java.util.UUID userId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank String action,
    @NotBlank @Size(min = 10, max = 2000) String reason,
    @NotBlank String password) {}
