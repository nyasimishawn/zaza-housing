package com.zaza.housing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ConsentWorkRequest(
    @NotNull UUID requestId,
    @Min(0) long version,
    @NotNull @AssertTrue Boolean consent,
    @NotBlank @Size(max = 2000) String message) {}
