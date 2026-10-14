package com.zaza.housing.model;

import com.zaza.housing.enums.VideoStatus;
import java.time.Instant;
import java.util.UUID;

public record PropertyVideo(UUID id, UUID propertyId, String storageKey, String contentType,
        int width, int height, double durationSeconds, long byteSize, String caption, VideoStatus status, long version,
        Instant createdAt, Instant updatedAt) { }
