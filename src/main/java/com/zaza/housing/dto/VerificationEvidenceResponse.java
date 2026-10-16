package com.zaza.housing.dto;

import com.zaza.housing.enums.DocumentCategory;
import java.util.UUID;

public record VerificationEvidenceResponse(
    UUID documentId,
    DocumentCategory category,
    String title,
    DocumentVersionResponse documentVersion) {}
