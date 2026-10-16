package com.zaza.housing.dto;

import com.zaza.housing.enums.InspectionKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateInspectionRequest(
    @NotNull UUID requestId,
    @NotNull InspectionKind kind,
    @NotNull @Valid InspectionDetails details) {}
