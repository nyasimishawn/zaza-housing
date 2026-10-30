package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationDeviceResponse(
    UUID id, String platform, String label, boolean active, Instant updatedAt) {}
