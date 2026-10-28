package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record AgencyMandateRequest(
    @NotNull UUID requestId,
    @NotNull UUID propertyId,
    @NotNull UUID agencyId,
    @NotNull @PositiveOrZero Long propertyVersion,
    @NotBlank @Size(min = 20, max = 2000) String responsibilities,
    @NotBlank @Size(min = 10, max = 2000) String feeTerms,
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    @NotNull @AssertTrue Boolean consent,
    @NotBlank String password) {}
