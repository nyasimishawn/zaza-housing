package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SupportKnowledgeRequest(
    @NotNull java.util.UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,79}") String slug,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 20, max = 8000) String body,
    @NotBlank String password) {}
