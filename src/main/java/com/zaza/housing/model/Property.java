package com.zaza.housing.model;

import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.PropertyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Property(UUID id, UUID ownerOrganizationId, UUID managerOrganizationId,
        String name, String description, PropertyType type, String countryCode, String city,
        String address, BigDecimal latitude, BigDecimal longitude, PropertyStatus status,
        long version, Instant createdAt, Instant updatedAt) { }
