package com.zaza.housing.model;

import java.util.UUID;
import org.springframework.core.io.Resource;

public record VideoContent(UUID id, Resource resource, String contentType, long byteSize) { }
