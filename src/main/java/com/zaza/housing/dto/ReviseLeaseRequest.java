package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ReviseLeaseRequest(@NotNull @PositiveOrZero Long version, @NotNull @Valid LeaseTerms terms) { }
