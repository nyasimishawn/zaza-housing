package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AgencyClientLinkRequest(
    @NotNull UUID requestId,
    @NotNull @PositiveOrZero Long version,
    @NotNull UUID enquiryId,
    @NotBlank @Size(max = 2000) String message) {}
