package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SupportMessageRequest(
    @NotNull java.util.UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 4000) String body,
    boolean internal) {}
