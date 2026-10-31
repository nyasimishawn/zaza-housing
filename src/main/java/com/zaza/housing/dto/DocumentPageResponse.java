package com.zaza.housing.dto;

import java.util.UUID;

public record DocumentPageResponse(UUID versionId, int pageNumber, int pageCount,
    String contentType, String contentHash, int width, int height, long byteSize, byte[] bytes) {}
