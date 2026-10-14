package com.zaza.housing.model;

import com.zaza.housing.enums.UnitAvailability;
import com.zaza.housing.enums.UnitStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PropertyUnit(UUID id, UUID propertyId, String label, int bedrooms, int bathrooms,
        BigDecimal floorAreaSqm, BigDecimal monthlyRent, BigDecimal deposit, String currency,
        UnitAvailability availability, UnitStatus status, long version, Instant createdAt, Instant updatedAt) { }
