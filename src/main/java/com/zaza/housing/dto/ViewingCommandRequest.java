package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ViewingCommandRequest(
    @NotNull UUID requestId, @Min(0) long version, @NotBlank @Size(max = 2000) String reason) {}
