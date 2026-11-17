package com.zaza.housing.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record CreateLeaseChangeRequest(@NotNull @PositiveOrZero Long version, @NotNull @Valid LeaseChangeTerms terms, @NotNull @Valid LeaseConsent evidence) { }
