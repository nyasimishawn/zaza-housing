package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InspectionDetails(
    @NotBlank @Size(max = 150) String title,
    @NotNull Instant observedAt,
    @NotBlank @Size(max = 4000) String summary,
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid InspectionItem> items,
    @NotNull @Size(max = 20) List<@NotNull @Valid InspectionMeter> meters,
    @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid InspectionKey> keys,
    @NotNull @Size(max = 30) List<@NotNull UUID> evidenceIds) {}
