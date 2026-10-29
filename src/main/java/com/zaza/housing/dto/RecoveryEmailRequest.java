package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record RecoveryEmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
