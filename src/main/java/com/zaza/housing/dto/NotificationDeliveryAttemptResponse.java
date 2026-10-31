package com.zaza.housing.dto;

import java.time.Instant;

public record NotificationDeliveryAttemptResponse(
    int attempt, String outcome, String errorCode, Instant createdAt) {}
