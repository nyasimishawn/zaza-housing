package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record RecoveryResetRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
    @NotBlank @Size(min = 12, max = 72) String newPassword,
    @Size(max = 64) String code,
    @Size(max = 64) String recoveryCode) {}
