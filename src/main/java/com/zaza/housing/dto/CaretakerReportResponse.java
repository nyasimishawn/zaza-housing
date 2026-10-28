package com.zaza.housing.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CaretakerReportResponse(
    Instant from,
    Instant to,
    boolean manager,
    Map<String, Long> currentTaskStates,
    long currentOverdueTasks,
    long tasksCreated,
    long tasksCompleted,
    Map<String, Long> inspectionActions,
    Map<String, Long> maintenanceActions,
    List<CaretakerPerformanceResponse> caretakers) {}
