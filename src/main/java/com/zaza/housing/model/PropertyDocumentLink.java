package com.zaza.housing.model;
import java.util.UUID;
import java.time.Instant;
public record PropertyDocumentLink(UUID id,UUID organizationId,UUID propertyId,UUID documentId,UUID documentVersionId,boolean detached,Instant createdAt) {}
