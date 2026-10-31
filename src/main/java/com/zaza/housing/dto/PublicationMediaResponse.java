package com.zaza.housing.dto;

import java.util.List;

public record PublicationMediaResponse(long listingVersion, List<ImageReferenceRequest> images, List<VideoReferenceRequest> videos) {}
