package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProviderProfileRequest(
    @Min(0) long version, @NotNull @Valid ProviderProfileDetails details) {}
