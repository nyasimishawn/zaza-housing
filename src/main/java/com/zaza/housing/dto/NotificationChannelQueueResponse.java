package com.zaza.housing.dto;

import java.time.Instant;

public record NotificationChannelQueueResponse(
    String channel, String status, long count, Instant oldest) {}
