package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReviewInspectionRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull UUID revisionId,
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String documentHash,
    @NotNull Boolean consent,
    @NotNull Boolean vacancyConfirmed,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Size(max = 2000) String reason) {}
