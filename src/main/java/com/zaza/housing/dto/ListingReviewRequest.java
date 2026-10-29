package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record ListingReviewRequest(
    @NotNull java.util.UUID requestId,
    @NotNull java.util.UUID listingId,
    @NotNull @PositiveOrZero Long listingVersion,
    @NotBlank @Size(min = 10, max = 2000) String reason,
    @NotBlank String password) {}
