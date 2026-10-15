package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, UUID applicationId, String type, String message, String applicationUrl, Instant createdAt, Instant readAt, UUID leaseId, String leaseUrl) { }
