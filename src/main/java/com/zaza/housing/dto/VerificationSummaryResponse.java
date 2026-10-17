package com.zaza.housing.dto;

import java.time.Instant;

public record VerificationSummaryResponse(String status, String method, Instant validUntil) {}
