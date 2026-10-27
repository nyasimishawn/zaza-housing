package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AgencyAssignmentRequest(
    @NotNull UUID requestId,
    @NotNull UUID propertyId,
    @NotNull UUID agentId,
    @PositiveOrZero Long version,
    @NotNull Boolean active,
    @NotBlank @Size(max = 2000) String message) {}
