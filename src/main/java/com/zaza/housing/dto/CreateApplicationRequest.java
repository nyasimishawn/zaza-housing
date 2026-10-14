package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record CreateApplicationRequest(@NotNull UUID listingId, @NotNull @PositiveOrZero Long listingVersion, @NotNull @Valid ApplicationDetails details) { }
