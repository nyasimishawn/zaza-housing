package com.zaza.housing.dto;

import com.zaza.housing.enums.OrganizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(@NotBlank @Size(max = 150) String name, @NotNull OrganizationType type) { }
