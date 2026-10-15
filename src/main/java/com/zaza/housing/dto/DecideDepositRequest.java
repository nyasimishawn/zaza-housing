package com.zaza.housing.dto;

import com.zaza.housing.enums.DepositDecision;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DecideDepositRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull DepositDecision decision,
    @NotBlank @Size(max = 2000) String reason) {}
