package com.zaza.housing.dto;

import com.zaza.housing.enums.VideoStatus;
import java.time.Instant;
import java.util.UUID;

public record PropertyVideoResponse(UUID id, String caption, String contentType, int width, int height, double durationSeconds,
        long byteSize, VideoStatus status, long version, String contentUrl, Instant createdAt, Instant updatedAt) { }
