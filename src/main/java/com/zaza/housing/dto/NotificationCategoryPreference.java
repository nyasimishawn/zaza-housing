package com.zaza.housing.dto;

import com.zaza.housing.enums.NotificationCategory;
import jakarta.validation.constraints.NotNull;

public record NotificationCategoryPreference(
    @NotNull NotificationCategory category,
    boolean inApp,
    boolean email,
    boolean sms,
    boolean push) {}
