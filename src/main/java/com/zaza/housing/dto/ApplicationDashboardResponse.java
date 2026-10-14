package com.zaza.housing.dto;

import com.zaza.housing.enums.ApplicationStatus;
import java.util.Map;

public record ApplicationDashboardResponse(Map<ApplicationStatus, Long> counts, long total, long needsAction) { }
