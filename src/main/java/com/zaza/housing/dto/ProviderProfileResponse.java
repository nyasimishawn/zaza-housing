package com.zaza.housing.dto;

import com.zaza.housing.enums.ProviderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProviderProfileResponse(
    UUID organizationId,
    ProviderProfileDetails details,
    ProviderStatus status,
    long version,
    String verificationStatus,
    long reviewCount,
    BigDecimal averageRating,
    String verificationMethod,
    Instant verificationValidUntil) {}
