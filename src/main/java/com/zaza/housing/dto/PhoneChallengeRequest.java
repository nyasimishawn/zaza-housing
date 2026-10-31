package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record PhoneChallengeRequest(
    @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{7,14}") String phone) {}
