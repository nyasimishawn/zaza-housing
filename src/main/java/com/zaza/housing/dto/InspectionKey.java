package com.zaza.housing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InspectionKey(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,49}") String key,
    @NotBlank @Size(max = 100) String label,
    @Min(0) @Max(100) int expectedCount,
    @Min(0) @Max(100) int transferredCount) {}
