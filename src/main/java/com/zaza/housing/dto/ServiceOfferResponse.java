package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceOfferStatus;
import java.util.UUID;

public record ServiceOfferResponse(
    UUID id,
    UUID providerId,
    long version,
    ServiceOfferStatus status,
    ServiceOfferDetails details) {}
