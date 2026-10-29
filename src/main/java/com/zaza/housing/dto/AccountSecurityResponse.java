package com.zaza.housing.dto;

public record AccountSecurityResponse(
    boolean emailVerified,
    boolean mfaEnabled,
    boolean mailReady,
    boolean encryptionReady,
    boolean privileged,
    long version,
    java.time.Instant adminVerifiedUntil,
    java.util.List<SecuritySessionResponse> sessions) {}
