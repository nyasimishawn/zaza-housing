package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record VideoUploadRequest(@NotNull UUID requestId, @PositiveOrZero long propertyVersion,
    @NotNull @Size(max=200) String caption) {}
