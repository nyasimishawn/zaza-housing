package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameOrganizationRequest(@NotBlank @Size(max = 150) String name) { }
