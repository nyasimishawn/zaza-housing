package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record VerificationDetails(
    @NotBlank @Size(max = 150) String claimedName,
    @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
    @NotBlank @Size(min = 20, max = 2000) String statement,
    @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> documentVersionIds) {}
