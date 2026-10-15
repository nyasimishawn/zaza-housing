package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkVisibility;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record WorkNoteRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull WorkVisibility visibility,
    @NotBlank @Size(max = 2000) String message) {}
