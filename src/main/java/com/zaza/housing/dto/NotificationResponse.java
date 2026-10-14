package com.zaza.housing.dto;

import com.zaza.housing.enums.ApplicationEventType;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, UUID applicationId, ApplicationEventType type, String message, String applicationUrl, Instant createdAt, Instant readAt) { }
