package com.zaza.housing.dto;

import java.util.Map;
import java.util.UUID;

public record CaretakerDashboardResponse(
    UUID organizationId,
    boolean manager,
    long properties,
    long invitations,
    Map<String, Long> taskCounts,
    long overdue,
    long dueToday) {}
