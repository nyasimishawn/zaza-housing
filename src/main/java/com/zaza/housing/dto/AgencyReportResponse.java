package com.zaza.housing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AgencyReportResponse(
    LocalDate from,
    LocalDate to,
    boolean manager,
    Map<String, Long> pipeline,
    long leadsCreated,
    long dealsWon,
    long tasksCompleted,
    long overdueTasks,
    List<AgencyAgentPerformance> agents,
    List<AgencyCommissionTotal> commissions) {}
