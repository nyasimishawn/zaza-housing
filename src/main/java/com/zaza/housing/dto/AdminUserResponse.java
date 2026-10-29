package com.zaza.housing.dto;

public record AdminUserResponse(
    java.util.UUID id,
    String email,
    String displayName,
    String platformRole,
    boolean active,
    boolean emailVerified,
    boolean mfaEnabled,
    long version,
    java.time.Instant createdAt) {}
