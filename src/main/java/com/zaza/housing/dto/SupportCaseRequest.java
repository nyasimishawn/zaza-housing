package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SupportCaseRequest(
    @NotNull java.util.UUID requestId,
    @NotBlank String kind,
    @NotBlank String category,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 20, max = 4000) String description,
    java.util.UUID listingId,
    @NotNull @AssertTrue Boolean consent) {}
