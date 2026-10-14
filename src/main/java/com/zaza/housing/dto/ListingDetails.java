package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ListingDetails(@NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 5000) String description, @NotNull Boolean showExactLocation) { }
