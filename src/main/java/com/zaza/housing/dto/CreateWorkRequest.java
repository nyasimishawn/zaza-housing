package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateWorkRequest(
    @NotNull UUID requestId,
    @NotNull UUID leaseId,
    @NotNull WorkKind kind,
    @NotNull ServiceCategory category,
    @NotNull WorkPriority priority,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 20, max = 8000) String description,
    UUID offerId,
    Long offerVersion) {}
