package com.zaza.housing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PublicServiceProviderResponse(
    UUID id,
    String displayName,
    String description,
    List<ServiceArea> areas,
    String verificationStatus,
    long reviewCount,
    BigDecimal averageRating) {}
