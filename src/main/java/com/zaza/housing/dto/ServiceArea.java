package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ServiceArea(
    @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
    @NotBlank @Size(max = 100) String city) {}
