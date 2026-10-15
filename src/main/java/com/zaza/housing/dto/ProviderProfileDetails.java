package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProviderProfileDetails(
    @NotBlank @Size(max = 150) String displayName,
    @NotBlank @Size(min = 20, max = 4000) String description,
    @NotEmpty @Size(max = 20) List<@NotNull @Valid ServiceArea> areas) {}
