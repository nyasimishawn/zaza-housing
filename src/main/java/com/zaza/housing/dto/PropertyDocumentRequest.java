package com.zaza.housing.dto;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
public record PropertyDocumentRequest(@NotNull UUID requestId,@NotNull UUID documentId,
 @NotNull UUID documentVersionId,@PositiveOrZero long propertyVersion) {}
