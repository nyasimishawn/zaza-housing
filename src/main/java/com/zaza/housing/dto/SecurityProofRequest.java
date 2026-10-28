package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SecurityProofRequest(
    @NotBlank String password, @Size(max = 64) String code, @Size(max = 64) String recoveryCode) {}
