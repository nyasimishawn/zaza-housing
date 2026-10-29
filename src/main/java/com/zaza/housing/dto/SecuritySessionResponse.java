package com.zaza.housing.dto;

public record SecuritySessionResponse(
    java.util.UUID id,
    java.time.Instant createdAt,
    java.time.Instant expiresAt,
    boolean current,
    java.time.Instant mfaVerifiedAt) {}
