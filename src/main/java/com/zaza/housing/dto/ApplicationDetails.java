package com.zaza.housing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ApplicationDetails(
        @NotNull @Min(1) @Max(20) Integer householdSize,
        @NotNull LocalDate requestedMoveInDate,
        @NotNull @Size(max = 2000) String message) { }
