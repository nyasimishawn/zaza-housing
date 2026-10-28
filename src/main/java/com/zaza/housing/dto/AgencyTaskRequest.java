package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record AgencyTaskRequest(
    @NotNull UUID requestId,
    @NotNull UUID leadId,
    @NotNull UUID assigneeId,
    @NotBlank @Size(max = 150) String title,
    @NotBlank String kind,
    @NotNull Instant dueAt,
    @NotBlank @Size(max = 2000) String description) {}
