package com.zaza.housing.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record OwnershipPreviewRequest(@NotNull @PositiveOrZero Long version,@NotNull @Valid OwnershipProposalTerms terms) { }
