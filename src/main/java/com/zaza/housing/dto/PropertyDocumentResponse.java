package com.zaza.housing.dto;
import java.util.UUID;
import java.time.Instant;
public record PropertyDocumentResponse(UUID id,DocumentResponse document,DocumentVersionResponse linkedVersion,Instant createdAt) {}
