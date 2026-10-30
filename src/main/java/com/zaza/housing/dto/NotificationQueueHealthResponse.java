package com.zaza.housing.dto;

import java.util.List;
import java.util.Map;

public record NotificationQueueHealthResponse(
    List<NotificationChannelQueueResponse> queues, Map<String, Boolean> channels) {}
