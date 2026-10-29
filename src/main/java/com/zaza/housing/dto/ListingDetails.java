package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ListingDetails(
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(max = 5000) String description,
    @NotNull Boolean showExactLocation,
    @Size(max = 30) java.util.List<@NotBlank @Size(max = 40) String> amenities) {
  public ListingDetails(String title, String description, Boolean showExactLocation) {
    this(title, description, showExactLocation, null);
  }
}
