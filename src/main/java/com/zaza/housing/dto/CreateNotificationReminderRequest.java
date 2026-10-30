package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record CreateNotificationReminderRequest(
    @NotNull UUID requestId,
    @NotBlank @Size(max = 140) String title,
    @NotNull Instant dueAt,
    @NotBlank @Pattern(regexp = "NONE|DAILY|WEEKLY") String repeatRule) {}
