package com.zaza.housing.dto;

import com.zaza.housing.model.RetainedMediaUsage;

public record MediaQuotaScopeResponse(
    RetainedMediaUsage usage, long byteLimit, int objectLimit, int dailyUploadLimit) {}
