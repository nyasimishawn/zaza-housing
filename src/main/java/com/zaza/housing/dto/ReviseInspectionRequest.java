package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReviseInspectionRequest(
    @NotNull UUID requestId, @Min(0) long version, @NotNull @Valid InspectionDetails details) {}
