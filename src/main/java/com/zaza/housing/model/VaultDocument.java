package com.zaza.housing.model;

import com.zaza.housing.enums.DocumentCategory;
import com.zaza.housing.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

public record VaultDocument(
    UUID id,
    UUID ownerUserId,
    UUID organizationId,
    DocumentCategory category,
    String title,
    DocumentStatus status,
    long version,
    UUID currentVersionId,
    Instant retainUntil,
    Instant createdAt,
    Instant updatedAt) {}
