package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record NotificationSettingsRequest(
    @Min(0) long version,
    @NotBlank @Size(max = 80) String timezone,
    @Min(0) @Max(1439) Integer quietStart,
    @Min(0) @Max(1439) Integer quietEnd,
    @NotBlank @Pattern(regexp = "INSTANT|DAILY") String emailMode,
    @NotNull @Size(min = 11, max = 11) List<@Valid NotificationCategoryPreference> categories) {}
