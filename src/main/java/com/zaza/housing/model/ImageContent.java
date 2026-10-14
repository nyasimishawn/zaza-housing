package com.zaza.housing.model;

import java.util.UUID;
import org.springframework.core.io.Resource;

public record ImageContent(UUID id, Resource resource, String contentType, long byteSize) { }
