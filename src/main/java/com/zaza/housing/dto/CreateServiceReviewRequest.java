package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateServiceReviewRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @Min(1) @Max(5) int rating,
    @NotBlank @Size(max = 2000) String comment,
    @NotNull @AssertTrue Boolean publishReview) {}
