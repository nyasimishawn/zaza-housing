package com.zaza.housing.dto;

import com.zaza.housing.enums.CaretakerCapability;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record CaretakerInvitationRequest(
    @NotNull UUID requestId,
    @NotNull UUID propertyId,
    @NotNull @jakarta.validation.constraints.PositiveOrZero Long propertyVersion,
    @NotNull UUID userId,
    @NotNull @Size(min = 1, max = 4) Set<@NotNull CaretakerCapability> capabilities,
    @NotNull Instant expiresAt,
    @NotBlank @Size(min = 20, max = 2000) String responsibilities,
    @NotNull @AssertTrue Boolean consent,
    @NotBlank String password) {}
