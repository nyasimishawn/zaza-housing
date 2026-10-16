package com.zaza.housing.dto;

import com.zaza.housing.enums.VerificationDecision;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record DecideVerificationRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull VerificationDecision decision,
    Instant validUntil,
    @NotNull Boolean checksConfirmed,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Size(min = 20, max = 2000) String reason) {}
