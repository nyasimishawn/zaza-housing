package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkStatus;
import java.util.Map;

public record WorkDashboardResponse(
    long total, Map<WorkStatus, Long> counts, long open, long overdueVisits) {}
