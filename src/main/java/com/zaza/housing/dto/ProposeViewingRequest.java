package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record ProposeViewingRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    @Min(0) long listingVersion,
    @NotBlank @Size(max = 1000) String meetingInstructions,
    @NotBlank @Size(max = 2000) String reason) {}
