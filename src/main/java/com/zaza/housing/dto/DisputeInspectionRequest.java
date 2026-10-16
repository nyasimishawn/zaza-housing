package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record DisputeInspectionRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull UUID revisionId,
    @NotBlank @Size(max = 2000) String reason,
    @NotNull @Size(max = 50) List<@NotBlank @Size(max = 50) String> itemKeys) {}
