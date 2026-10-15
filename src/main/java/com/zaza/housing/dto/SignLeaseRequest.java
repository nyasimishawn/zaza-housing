package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record SignLeaseRequest(@NotNull @PositiveOrZero Long version, @NotNull UUID revisionId, @NotNull @Valid LeaseConsent evidence) { }
