package com.zaza.housing.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record CreateOwnershipProposalRequest(@NotNull @PositiveOrZero Long version,@NotNull @Valid OwnershipProposalTerms terms,@NotNull @Valid LeaseConsent evidence) { }
