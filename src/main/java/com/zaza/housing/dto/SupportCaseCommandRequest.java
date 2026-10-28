package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SupportCaseCommandRequest(
    @NotNull java.util.UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(min = 10, max = 2000) String message) {}
