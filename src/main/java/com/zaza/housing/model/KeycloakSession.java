package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record KeycloakSession(String tokenHash, UUID userId, String subject,
    String encryptedRefreshToken, Instant sessionExpiresAt, Instant mfaVerifiedAt) { }
