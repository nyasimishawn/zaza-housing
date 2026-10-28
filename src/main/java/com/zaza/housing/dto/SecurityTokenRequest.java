package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record SecurityTokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
