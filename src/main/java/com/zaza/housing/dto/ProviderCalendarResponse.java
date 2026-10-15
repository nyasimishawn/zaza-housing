package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkStatus;
import java.time.Instant;
import java.util.UUID;

public record ProviderCalendarResponse(
    UUID jobId, Instant startAt, Instant endAt, WorkStatus status) {}
