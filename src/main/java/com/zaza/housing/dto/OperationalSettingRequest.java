package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record OperationalSettingRequest(
    @NotNull java.util.UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotNull Integer value,
    @NotBlank @Size(min = 10, max = 2000) String reason,
    @NotBlank String password) {}
