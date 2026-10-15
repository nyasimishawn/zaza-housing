package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record ProposeWorkVisitRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    @NotBlank @Size(max = 2000) String accessInstructions) {}
