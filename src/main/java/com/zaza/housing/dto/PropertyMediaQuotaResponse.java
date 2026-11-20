package com.zaza.housing.dto;

public record PropertyMediaQuotaResponse(
    MediaQuotaScopeResponse account,
    MediaQuotaScopeResponse owningOrganization,
    long activeImages,
    int activeImageLimit,
    long retainedImages,
    int retainedImageLimit,
    long activeVideos,
    int activeVideoLimit,
    long retainedVideos,
    int retainedVideoLimit) {}
