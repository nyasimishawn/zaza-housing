package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record AgencyClientRequest(
    @NotNull UUID requestId,
    @PositiveOrZero Long version,
    @NotNull @Valid AgencyClientDetails details,
    UUID enquiryId) {}
