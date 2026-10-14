package com.zaza.housing.dto;

import com.zaza.housing.enums.ImageStatus;
import java.time.Instant;
import java.util.UUID;

public record PropertyImageResponse(UUID id, String altText, String contentType, int width, int height,
        long byteSize, ImageStatus status, long version, String contentUrl, Instant createdAt, Instant updatedAt) { }
