package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReviseVerificationRequest(
    @NotNull UUID requestId, @Min(0) long version, @NotNull @Valid VerificationDetails details) {}
