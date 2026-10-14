package com.zaza.housing.dto;

import com.zaza.housing.enums.PropertyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

// An explicit public projection; private property/unit records are never serialized to guests.
public record PublicListingResponse(UUID id, String title, String description, PropertyType propertyType,
        String countryCode, String city, String address, BigDecimal latitude, BigDecimal longitude,
        int bedrooms, int bathrooms, BigDecimal floorAreaSqm, BigDecimal monthlyRent,
        BigDecimal deposit, String currency, Instant publishedAt, List<PublicImageResponse> images, List<PublicVideoResponse> videos) {
    public PublicListingResponse { images = images == null ? List.of() : List.copyOf(images);
        videos = videos == null ? List.of() : List.copyOf(videos); }
}
