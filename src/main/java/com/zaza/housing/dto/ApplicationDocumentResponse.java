package com.zaza.housing.dto;

import com.zaza.housing.enums.DocumentCategory;
import java.time.Instant;
import java.util.UUID;

public record ApplicationDocumentResponse(
    UUID id,
    UUID documentId,
    DocumentCategory category,
    String title,
    DocumentVersionResponse documentVersion,
    long version,
    String purpose,
    Instant expiresAt,
    boolean revoked,
    boolean accessible,
    Instant createdAt) {}
