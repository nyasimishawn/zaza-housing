package com.zaza.housing.dto;

import com.zaza.housing.enums.DocumentCategory;
import com.zaza.housing.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
    UUID id,
    DocumentCategory category,
    String title,
    DocumentStatus status,
    long version,
    Instant retainUntil,
    DocumentVersionResponse currentVersion,
    Instant createdAt,
    Instant updatedAt) {}
