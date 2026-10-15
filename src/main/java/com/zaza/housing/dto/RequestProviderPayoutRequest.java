package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RequestProviderPayoutRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull UUID destinationId,
    @NotBlank @Size(max = 2000) String reason) {}
