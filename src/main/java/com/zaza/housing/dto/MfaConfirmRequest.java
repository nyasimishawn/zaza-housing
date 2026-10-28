package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record MfaConfirmRequest(
    @PositiveOrZero long version,
    @NotBlank String password,
    @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
