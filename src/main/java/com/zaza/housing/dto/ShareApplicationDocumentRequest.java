package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record ShareApplicationDocumentRequest(
    @NotNull UUID requestId,
    @Min(0) long applicationVersion,
    @NotNull UUID documentId,
    @NotNull UUID documentVersionId,
    @NotNull Instant expiresAt,
    @NotNull Boolean consent) {}
