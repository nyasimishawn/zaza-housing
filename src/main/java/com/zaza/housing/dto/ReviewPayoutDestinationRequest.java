package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReviewPayoutDestinationRequest(
    @NotNull UUID requestId,
    @NotBlank @Pattern(regexp = "VERIFIED|REVOKED") String status,
    @NotBlank @Size(min = 12, max = 200) String password,
    @NotBlank @Size(max = 2000) String reason) {}
