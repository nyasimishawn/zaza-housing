package com.zaza.housing.dto;

import java.util.UUID;

public record CaretakerPerformanceResponse(
    UUID userId, String displayName, long completedTasks, long submittedTasks, long openTasks) {}
