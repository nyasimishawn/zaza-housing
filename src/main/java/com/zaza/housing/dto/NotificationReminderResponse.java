package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationReminderResponse(
    UUID id,
    String title,
    Instant dueAt,
    String repeatRule,
    String status,
    long version,
    Instant expiresAt) {}
