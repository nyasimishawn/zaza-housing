package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record UpdateNotificationReminderRequest(
    @Min(0) long version,
    @NotBlank @Size(max = 140) String title,
    @NotNull Instant dueAt,
    @NotBlank @Pattern(regexp = "NONE|DAILY|WEEKLY") String repeatRule) {}
