package com.zaza.housing.listing;

import com.zaza.housing.property.PropertyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// An explicit public projection; private property/unit records are never serialized to guests.
public record PublicListing(UUID id, String title, String description, PropertyType propertyType,
        String countryCode, String city, String address, BigDecimal latitude, BigDecimal longitude,
        int bedrooms, int bathrooms, BigDecimal floorAreaSqm, BigDecimal monthlyRent,
        BigDecimal deposit, String currency, Instant publishedAt) { }
