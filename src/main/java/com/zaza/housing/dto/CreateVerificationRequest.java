package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateVerificationRequest(
    @NotNull UUID requestId, @NotNull @Valid VerificationDetails details) {}
