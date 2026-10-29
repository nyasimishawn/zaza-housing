package com.zaza.housing.dto;

public record MfaEnrollmentResponse(
    String secret, String otpAuthUri, long version, java.time.Instant expiresAt) {}
