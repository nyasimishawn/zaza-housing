package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record LeaseTerminationPreviewRequest(@NotNull @PositiveOrZero Long version, @NotNull LocalDate proposedEndDate, @NotBlank @Size(max = 2000) String message) { }
