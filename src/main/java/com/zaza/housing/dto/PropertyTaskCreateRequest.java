package com.zaza.housing.dto;

import com.zaza.housing.enums.PropertyTaskKind;
import com.zaza.housing.enums.WorkPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record PropertyTaskCreateRequest(
    @NotNull UUID requestId,
    @NotNull UUID propertyId,
    UUID unitId,
    @NotNull UUID assigneeId,
    @NotNull PropertyTaskKind kind,
    @NotNull WorkPriority priority,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 10, max = 4000) String description,
    @Size(max = 150) String locationLabel,
    @NotNull Instant dueAt) {}
