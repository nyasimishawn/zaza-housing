package com.zaza.housing.dto;

import java.util.UUID;

public record AgencyAgentPerformance(
    UUID id,
    String displayName,
    long leadsCreated,
    long dealsWon,
    long tasksCompleted,
    long openTasks) {}
