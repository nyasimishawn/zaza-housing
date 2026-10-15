package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record CreateLeaseRequest(@NotNull UUID applicationId, @NotNull @PositiveOrZero Long applicationVersion, @NotNull @Valid LeaseTerms terms) { }
