package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import java.time.Instant;
import java.util.UUID;

public record WorkEventResponse(
    UUID id,
    String type,
    WorkStatus status,
    WorkParty party,
    WorkVisibility visibility,
    String message,
    Instant createdAt) {}
