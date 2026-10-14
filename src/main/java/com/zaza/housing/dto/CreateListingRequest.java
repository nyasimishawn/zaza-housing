package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateListingRequest(@NotNull UUID unitId, @NotNull @Valid ListingDetails details) { }
