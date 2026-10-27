package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AgencyLeadCreateRequest(
    @NotNull UUID requestId,
    @NotNull UUID clientId,
    UUID propertyId,
    UUID listingId,
    UUID enquiryId,
    UUID agentId,
    @NotBlank @Size(max = 150) String title) {}
