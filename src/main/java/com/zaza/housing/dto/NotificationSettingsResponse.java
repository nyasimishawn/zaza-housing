package com.zaza.housing.dto;

import java.util.List;
import java.util.Map;

public record NotificationSettingsResponse(
    long version,
    String timezone,
    Integer quietStart,
    Integer quietEnd,
    String emailMode,
    List<NotificationCategoryPreference> categories,
    boolean emailVerified,
    String phone,
    boolean phoneVerified,
    Map<String, Boolean> channels) {}
