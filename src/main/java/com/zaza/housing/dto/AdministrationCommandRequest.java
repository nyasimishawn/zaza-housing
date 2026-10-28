package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record AdministrationCommandRequest(
    @NotNull java.util.UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(min = 10, max = 2000) String reason,
    @NotBlank String password,
    java.util.UUID assigneeId,
    String priority,
    @Size(max = 4000) String resolution,
    @PositiveOrZero Long sourceVersion) {}
