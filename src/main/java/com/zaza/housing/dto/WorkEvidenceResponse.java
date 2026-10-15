package com.zaza.housing.dto;

import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkVisibility;
import java.time.Instant;
import java.util.UUID;

public record WorkEvidenceResponse(
    UUID id,
    WorkParty party,
    WorkVisibility visibility,
    String caption,
    String contentType,
    int width,
    int height,
    long byteSize,
    Instant createdAt) {}
