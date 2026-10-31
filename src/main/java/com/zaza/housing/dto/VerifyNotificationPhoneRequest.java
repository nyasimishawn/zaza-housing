package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record VerifyNotificationPhoneRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
