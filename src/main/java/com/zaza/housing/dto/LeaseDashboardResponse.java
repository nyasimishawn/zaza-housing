package com.zaza.housing.dto;

import com.zaza.housing.enums.LeaseStatus;
import java.util.Map;

public record LeaseDashboardResponse(Map<LeaseStatus, Long> counts, long total, long awaitingSignature, long awaitingCheckout) { }
