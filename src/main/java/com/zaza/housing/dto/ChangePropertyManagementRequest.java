package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record ChangePropertyManagementRequest(@NotNull @PositiveOrZero Long version, UUID managerOrganizationId) { }
