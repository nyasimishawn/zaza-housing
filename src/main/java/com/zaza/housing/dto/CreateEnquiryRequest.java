package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateEnquiryRequest(
    @NotNull UUID requestId,
    @Min(0) long listingVersion,
    @NotBlank @Size(max = 4000) String message) {}
