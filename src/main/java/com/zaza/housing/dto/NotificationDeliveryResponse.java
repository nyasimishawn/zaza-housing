package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationDeliveryResponse(
    UUID id,
    UUID notificationId,
    String channel,
    String purpose,
    String category,
    String status,
    int attempts,
    Instant createdAt,
    Instant availableAt,
    String errorCode) {}
