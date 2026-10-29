package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SecurityPasswordRequest(
    @NotBlank String password,
    @NotBlank @Size(min = 12, max = 72) String newPassword,
    @Size(max = 64) String code,
    @Size(max = 64) String recoveryCode) {}
