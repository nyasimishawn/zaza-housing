package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkPriority;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TriageWorkRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull WorkPriority priority,
    @NotBlank @Size(max = 2000) String message) {}
