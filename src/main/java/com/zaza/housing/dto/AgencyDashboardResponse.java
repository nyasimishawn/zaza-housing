package com.zaza.housing.dto;

import java.util.Map;

public record AgencyDashboardResponse(
    boolean manager,
    long properties,
    long clients,
    Map<String, Long> stages,
    long dueToday,
    long overdue,
    long unassigned) {}
