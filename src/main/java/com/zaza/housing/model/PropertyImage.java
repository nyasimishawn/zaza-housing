package com.zaza.housing.model;

import com.zaza.housing.enums.ImageStatus;
import java.time.Instant;
import java.util.UUID;

public record PropertyImage(UUID id, UUID propertyId, String storageKey, String contentType,
        int width, int height, long byteSize, String altText, ImageStatus status, long version,
        Instant createdAt, Instant updatedAt) { }
