package com.zaza.housing.model;

import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkVisibility;
import java.time.Instant;
import java.util.UUID;

public record WorkEvidence(
    UUID id,
    UUID workId,
    UUID assignmentId,
    UUID providerId,
    WorkParty party,
    WorkVisibility visibility,
    String caption,
    String storageKey,
    String contentType,
    int width,
    int height,
    long byteSize,
    Instant createdAt) {}
